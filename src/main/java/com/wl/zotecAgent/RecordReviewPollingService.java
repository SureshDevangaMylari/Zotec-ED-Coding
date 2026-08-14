package com.wl.zotecAgent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.wl.util.JsonFileUtil;

/**
 * Polls {@code GET /records/{record_id}/review} with desk auth
 * ({@code x-bot-secret}, {@code x-desktop-id} — same as upload / bot poll).
 * Retries every 5s while {@code review_status} is {@code pending} or {@code reviewed_result} is null.
 */
@Service
public class RecordReviewPollingService {

    private static final Logger log = LoggerFactory.getLogger(RecordReviewPollingService.class);
    private static final String REVIEW_SAVE_DIR = "resources/jsonfolder";

    private final RestTemplate restTemplate;

    @Value("${document.review.base-url}")
    private String reviewBaseUrl;

    @Value("${bot.poll.desktop-id}")
    private String desktopId;

    @Value("${bot.poll.secret}")
    private String botSecret;

    @Value("${document.review.poll-interval-seconds}")
    private long pollIntervalSeconds;

    @Value("${batch.resume.timeout-minutes}")
    private long timeoutMinutes;

    public RecordReviewPollingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Map<String, Object> reviewUploadPdf(String recordId) throws TimeoutException, InterruptedException {
        return pollUntilReviewReady(recordId);
    }

    /**
     * @return map with full {@code review_response} plus {@code patient}/{@code cpt}/{@code icd}/{@code ed}
     *         extracted from {@code reviewed_result} for {@link Flow}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> pollUntilReviewReady(String recordId) throws TimeoutException, InterruptedException {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("record_id is required for review polling");
        }

        String url = reviewBaseUrl.replaceAll("/+$", "") + "/records/" + recordId + "/review";
        HttpHeaders headers = DocumentProcessorAuth.uploadHeaders(desktopId, botSecret);
        Instant deadline = Instant.now().plus(Duration.ofMinutes(timeoutMinutes));
        int attempt = 0;

        log.info("Review GET {} ({}={}, {}={}, timeout {} min, interval {}s)",
                url,
                DocumentProcessorAuth.DESKTOP_ID_HEADER, desktopId,
                DocumentProcessorAuth.BOT_SECRET_HEADER, DocumentProcessorAuth.maskSecret(botSecret),
                timeoutMinutes, pollIntervalSeconds);

        while (Instant.now().isBefore(deadline)) {
            attempt++;
            Map<String, Object> body = fetchReviewBody(url, headers, recordId, attempt);
            if (body != null) {
                if (isReviewPending(body)) {
                    log.info("Review pending record_id={} attempt={} status={} — retry in {}s",
                            recordId, attempt, body.get("review_status"), pollIntervalSeconds);
                    Thread.sleep(Duration.ofSeconds(pollIntervalSeconds).toMillis());
                    continue;
                }

                saveReviewResponse(recordId, body, attempt);
                log.info("Review complete record_id={} after {} attempt(s), status={}",
                        recordId, attempt, body.get("review_status"));
                return buildResult(body, attempt);
            }

            Thread.sleep(Duration.ofSeconds(pollIntervalSeconds).toMillis());
        }

        throw new TimeoutException("Timed out after " + timeoutMinutes + " minute(s) waiting for review record_id="
                + recordId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchReviewBody(String url, HttpHeaders headers, String recordId, int attempt) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getStatusCode().value() == 200) {
                return response.getBody() != null ? response.getBody() : Map.of();
            }
            log.warn("Review poll record_id={} attempt={} unexpected HTTP {}, retrying",
                    recordId, attempt, response.getStatusCode().value());
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 400) {
                log.info("Review not ready (HTTP 400) record_id={} attempt={} — retry in {}s",
                        recordId, attempt, pollIntervalSeconds);
            } else {
                log.warn("Review poll record_id={} HTTP {} — {}", recordId, status, e.getResponseBodyAsString());
                throw e;
            }
        }
        return null;
    }

    private static boolean isReviewPending(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return true;
        }
        String status = stringVal(body.get("review_status"));
        if ("pending".equalsIgnoreCase(status)) {
            return true;
        }
        Object reviewed = body.get("reviewed_result");
        if (reviewed == null) {
            return true;
        }
        if (reviewed instanceof Map<?, ?> map && map.isEmpty()) {
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildResult(Map<String, Object> reviewBody, int attempt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("review_response", reviewBody);
        result.put("poll_attempts", attempt);
        result.put("record_id", reviewBody.get("record_id"));
        result.put("review_status", reviewBody.get("review_status"));

        Map<String, Object> flowPayload = extractFlowPayload(reviewBody);
        result.putAll(flowPayload);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFlowPayload(Map<String, Object> reviewBody) {
        Object reviewed = reviewBody.get("reviewed_result");
        if (reviewed instanceof Map<?, ?> reviewedMap && !reviewedMap.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) reviewedMap);
            if (payload.containsKey("patient") || payload.containsKey("cpt") || payload.containsKey("icd")) {
                return payload;
            }
            Object data = payload.get("data");
            if (data instanceof Map<?, ?> dataMap && !dataMap.isEmpty()) {
                return new LinkedHashMap<>((Map<String, Object>) dataMap);
            }
            return payload;
        }

        Object data = reviewBody.get("data");
        if (data instanceof Map<?, ?> dataMap && !dataMap.isEmpty()) {
            return new LinkedHashMap<>((Map<String, Object>) dataMap);
        }

        if (reviewBody.containsKey("patient") || reviewBody.containsKey("cpt")) {
            return new LinkedHashMap<>(reviewBody);
        }
        return Map.of();
    }

    private void saveReviewResponse(String recordId, Map<String, Object> body, int attempt) {
        try {
            Map<String, Object> toSave = new LinkedHashMap<>(body);
            toSave.put("_poll_attempts", attempt);
            toSave.put("_review_url", reviewBaseUrl.replaceAll("/+$", "") + "/records/" + recordId + "/review");
            String fileName = "review-" + sanitizeFileName(recordId) + ".json";
            String path = JsonFileUtil.saveToJsonFile(toSave, REVIEW_SAVE_DIR, fileName, true);
            log.info("Saved review response to {}", path);

            String json = JsonFileUtil.toJsonString(body, true);
            System.out.println("=== Review response ===");
            System.out.println(json);
            System.out.println("Saved: " + path);
            System.out.println("=======================");
        } catch (Exception e) {
            log.warn("Could not save review response for record_id={}: {}", recordId, e.getMessage());
        }
    }

    private static String sanitizeFileName(String recordId) {
        return recordId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String stringVal(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
