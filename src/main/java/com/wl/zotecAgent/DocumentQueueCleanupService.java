package com.wl.zotecAgent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Clears leftover documents via {@code GET /documents} and
 * {@code DELETE /documents/{document_id}} before review polling.
 * Authenticates first with {@code POST /auth/login}. Subsequent calls use desk
 * headers only ({@code x-desktop-id}, {@code x-bot-secret}) — no session cookie.
 */
@Service
public class DocumentQueueCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentQueueCleanupService.class);

    private final RestTemplate restTemplate;

    @Value("${document.review.base-url}")
    private String baseUrl;

    @Value("${document.auth.login-url}")
    private String authLoginUrl;

    /** Hardcoded document-API credentials (not Zotec portal login). */
    @Value("${document.auth.username}")
    private String authUsername;

    @Value("${document.auth.password}")
    private String authPassword;

    @Value("${bot.poll.desktop-id}")
    private String desktopId;

    @Value("${bot.poll.secret}")
    private String botSecret;

    public DocumentQueueCleanupService(RestTemplate restTemplate) {
	this.restTemplate = restTemplate;
    }

    /**
     * Deletes leftover queued documents, keeping only the latest so Start processing
     * works that chart. If the queue is empty or has a single document, nothing is deleted.
     *
     * @return kept {@code document_id}, or {@code null} if the queue was empty
     */
    public String clearQueuedDocumentsKeepLatest() {
	List<QueuedDocument> queued = listQueuedDocuments();
	if (queued.isEmpty()) {
	    log.info("No documents in queue — continue to desktop review");
	    return null;
	}

	QueuedDocument latest = pickLatest(queued);
	log.info("Found {} document(s) in queue — keeping latest document_id={} and deleting the rest: {}",
		queued.size(), latest.id, queued.stream().map(d -> d.id).toList());

	HttpHeaders headers = DocumentProcessorAuth.uploadHeaders(desktopId, botSecret);
	for (QueuedDocument doc : queued) {
	    if (latest.id.equalsIgnoreCase(doc.id)) {
		log.info("Skipping DELETE for latest document_id={}", doc.id);
		continue;
	    }
	    deleteDocument(doc.id, headers);
	}
	return latest.id;
    }

    /**
     * Lists documents in the queue; deletes each id one-by-one.
     * Skips {@code keepDocumentId} (the record about to be reviewed) if present.
     * If the queue is empty, does nothing — caller continues to review.
     */
    public void clearQueuedDocumentsExcept(String keepDocumentId) {
	List<QueuedDocument> queued = listQueuedDocuments();
	List<String> documentIds = queued.stream().map(d -> d.id).toList();
	if (documentIds.isEmpty()) {
	    log.info("No documents in queue — continue to review API");
	    return;
	}

	log.info("Found {} document(s) in queue — deleting one by one: {}", documentIds.size(), documentIds);

	HttpHeaders headers = DocumentProcessorAuth.uploadHeaders(desktopId, botSecret);
	for (String documentId : documentIds) {
	    if (keepDocumentId != null && keepDocumentId.equalsIgnoreCase(documentId)) {
		log.info("Skipping DELETE for current upload document_id={}", documentId);
		continue;
	    }
	    deleteDocument(documentId, headers);
	}
    }

    @SuppressWarnings("unchecked")
    private List<QueuedDocument> listQueuedDocuments() {
	try {
	    loginForDocumentApi();
	} catch (Exception e) {
	    log.warn("POST /auth/login failed — continuing without document cleanup: {}",
		    e.getMessage());
	    return List.of();
	}

	String listUrl = baseUrl.replaceAll("/+$", "") + "/documents";
	HttpHeaders headers = DocumentProcessorAuth.uploadHeaders(desktopId, botSecret);

	log.info("Checking document queue: GET {} ({}={})",
		listUrl, DocumentProcessorAuth.DESKTOP_ID_HEADER, desktopId);

	try {
	    ResponseEntity<Map> response = restTemplate.exchange(
		    listUrl,
		    HttpMethod.GET,
		    new HttpEntity<>(headers),
		    Map.class);
	    Map<String, Object> body = response.getBody();
	    log.info("GET /documents response: {}", body);

	    List<QueuedDocument> documents = new ArrayList<>();
	    if (body != null) {
		Object docsObj = body.get("documents");
		if (docsObj instanceof List<?> docs) {
		    int index = 0;
		    for (Object item : docs) {
			if (!(item instanceof Map<?, ?> m)) {
			    index++;
			    continue;
			}
			Object id = m.get("document_id");
			if (id == null) {
			    id = m.get("documentId");
			}
			if (id == null) {
			    id = m.get("id");
			}
			if (id != null) {
			    String s = String.valueOf(id).trim();
			    if (!s.isEmpty()) {
				documents.add(new QueuedDocument(s, createdEpoch(m), index));
			    }
			}
			index++;
		    }
		}
	    }
	    return documents;
	} catch (Exception e) {
	    log.warn("GET /documents failed — continuing without cleanup: {}", e.getMessage());
	    return List.of();
	}
    }

    private static QueuedDocument pickLatest(List<QueuedDocument> queued) {
	QueuedDocument latest = queued.get(queued.size() - 1);
	for (QueuedDocument doc : queued) {
	    if (doc.createdEpoch > latest.createdEpoch
		    || (doc.createdEpoch == latest.createdEpoch && doc.index > latest.index)) {
		latest = doc;
	    }
	}
	return latest;
    }

    private static long createdEpoch(Map<?, ?> m) {
	for (String key : List.of("created_at", "createdAt", "uploaded_at", "uploadedAt",
		"created", "timestamp", "ingested_at")) {
	    Object v = m.get(key);
	    if (v instanceof Number n) {
		long x = n.longValue();
		return x < 1_000_000_000_000L ? x * 1000L : x;
	    }
	    if (v instanceof String s && !s.isBlank()) {
		try {
		    return java.time.Instant.parse(s).toEpochMilli();
		} catch (Exception ignored) {
		}
		try {
		    return Long.parseLong(s.trim());
		} catch (Exception ignored) {
		}
	    }
	}
	return Long.MIN_VALUE;
    }

    private static final class QueuedDocument {
	final String id;
	final long createdEpoch;
	final int index;

	QueuedDocument(String id, long createdEpoch, int index) {
	    this.id = id;
	    this.createdEpoch = createdEpoch;
	    this.index = index;
	}
    }

    /**
     * {@code POST /auth/login} — keeps the login step. Session cookie from the
     * server is not read, stored, logged, or forwarded on later requests.
     */
    @SuppressWarnings("unchecked")
    private void loginForDocumentApi() {
	HttpHeaders headers = new HttpHeaders();
	headers.setContentType(MediaType.APPLICATION_JSON);
	headers.setAccept(List.of(MediaType.APPLICATION_JSON));

	Map<String, String> body = new LinkedHashMap<>();
	body.put("username", authUsername);
	body.put("password", authPassword);

	log.info("Document API login: POST {} (username={})", authLoginUrl, authUsername);

	ResponseEntity<Map> response = restTemplate.exchange(
		authLoginUrl,
		HttpMethod.POST,
		new HttpEntity<>(body, headers),
		Map.class);

	if (!response.getStatusCode().is2xxSuccessful()) {
	    throw new IllegalStateException("auth/login failed status=" + response.getStatusCode().value());
	}
	log.info("Document API login succeeded status={}", response.getStatusCode().value());
    }

    private void deleteDocument(String documentId, HttpHeaders headers) {
	String deleteUrl = baseUrl.replaceAll("/+$", "") + "/documents/" + documentId;
	try {
	    log.info("DELETE {}", deleteUrl);
	    ResponseEntity<String> response = restTemplate.exchange(
		    deleteUrl,
		    HttpMethod.DELETE,
		    new HttpEntity<>(headers),
		    String.class);
	    log.info("Deleted document_id={} status={} body={}",
		    documentId, response.getStatusCode().value(), response.getBody());
	} catch (Exception e) {
	    log.warn("Failed to DELETE document_id={}: {}", documentId, e.getMessage());
	}
    }
}
