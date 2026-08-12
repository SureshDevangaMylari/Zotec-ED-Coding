package com.wl.zotecAgent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.Page;
import com.wl.util.JsonFileUtil;

/**
 * Uploads documents to the document processor, then polls
 * {@code GET /records/{record_id}/review} until HTTP 200.
 */
@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final ImagePdfUploadService imagePdfUploadService;
    private final RecordReviewPollingService recordReviewPollingService;
    private final DocumentQueueCleanupService documentQueueCleanupService;

    @Value("${batch.resume.timeout-minutes:60}")
    private long batchResumeTimeoutMinutes;

    public DocumentProcessingService(
            ImagePdfUploadService imagePdfUploadService,
            RecordReviewPollingService recordReviewPollingService,
            DocumentQueueCleanupService documentQueueCleanupService) {
        this.imagePdfUploadService = imagePdfUploadService;
        this.recordReviewPollingService = recordReviewPollingService;
        this.documentQueueCleanupService = documentQueueCleanupService;
    }

    /**
     * Collect images → PDF upload (with workfile {@code metadata}) → review poll.
     *
     * @param uploadMetadata well + Select client(s) {@code client_location}; may be null/empty
     */
    public Map<String, Object> uploadPdfAndAwaitResume(Page page, Map<String, Object> uploadMetadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<ImagePdfUploadService.PageImage> images = imagePdfUploadService.collectImagesFromPage(page);
            if (images.isEmpty()) {
                log.warn("No images collected for PDF upload");
                result.put("upload_error", "no images on page");
                return result;
            }
            String baseName = "batch_" + System.currentTimeMillis();
            Path pdfPath = imagePdfUploadService.buildPdfFromImages(images, baseName);
            result.put("pdf_path", pdfPath.toString().replace('\\', '/'));
            result.put("pdf_page_count", images.size());
            if (uploadMetadata != null && !uploadMetadata.isEmpty()) {
                result.put("upload_metadata", uploadMetadata);
            }

            Map<String, Object> uploadResult = imagePdfUploadService.uploadFile(pdfPath, uploadMetadata);
            result.put("upload_response", uploadResult);
            printUploadResponse(uploadResult);

            String recordId = resolveRecordId(uploadResult, baseName);
            result.put("record_id", recordId);
            result.put("document_id", recordId);
            result.put("batch_id", recordId);

            log.info("Upload record_id={} — clearing document queue then polling review", recordId);
            documentQueueCleanupService.clearQueuedDocumentsExcept(recordId);

            Map<String, Object> reviewResult = recordReviewPollingService.reviewUploadPdf(recordId);
            result.put("review_result", reviewResult);
            result.put("review_response", reviewResult.get("review_response"));

            @SuppressWarnings("unchecked")
            Map<String, Object> resumePayload = new LinkedHashMap<>(reviewResult);
            resumePayload.remove("review_response");
            resumePayload.remove("poll_attempts");
            result.put("resume_payload", resumePayload);

            try {
                JsonFileUtil.saveToJsonFileAtPath(resumePayload, "resources/jsonfolder/output.json", true);
            } catch (Exception e) {
                log.warn("Could not save resume payload to output.json: {}", e.getMessage());
            }
        } catch (TimeoutException e) {
            log.error("Timed out polling review for record_id={} after {} minute(s)",
                    result.get("record_id"), batchResumeTimeoutMinutes, e);
            result.put("resume_error", "timeout after " + batchResumeTimeoutMinutes + " minutes");
        } catch (Exception e) {
            log.error("PDF upload / review poll failed: {}", e.getMessage(), e);
            result.put("upload_error", e.getMessage());
        }
        return result;
    }

    /** Backward-compatible: upload PDF with no metadata. */
    public Map<String, Object> uploadPdfAndAwaitResume(Page page) {
        return uploadPdfAndAwaitResume(page, null);
    }

    /**
     * Saves dictated report text, uploads with workfile {@code metadata}, polls review.
     *
     * @param uploadMetadata well + Select client(s) {@code client_location}; may be null/empty
     */
    public Map<String, Object> uploadTextAndAwaitResume(String text, Map<String, Object> uploadMetadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (text == null || text.isBlank()) {
                log.warn("No dictated report text to upload");
                result.put("upload_error", "empty dictated report text");
                return result;
            }
            String baseName = "report_" + System.currentTimeMillis();
            Path textPath = imagePdfUploadService.saveTextFile(text, baseName);
            result.put("text_path", textPath.toString().replace('\\', '/'));
            result.put("text_length", text.length());
            if (uploadMetadata != null && !uploadMetadata.isEmpty()) {
                result.put("upload_metadata", uploadMetadata);
            }

            Map<String, Object> uploadResult = imagePdfUploadService.uploadFile(textPath, uploadMetadata);
            result.put("upload_response", uploadResult);
            printUploadResponse(uploadResult, "text");

            String recordId = resolveRecordId(uploadResult, baseName);
            result.put("record_id", recordId);
            result.put("document_id", recordId);
            result.put("batch_id", recordId);

            log.info("Upload record_id={} — clearing document queue then polling review", recordId);
            documentQueueCleanupService.clearQueuedDocumentsExcept(recordId);

            Map<String, Object> reviewResult = recordReviewPollingService.reviewUploadPdf(recordId);
            result.put("review_result", reviewResult);
            result.put("review_response", reviewResult.get("review_response"));

            Map<String, Object> resumePayload = new LinkedHashMap<>(reviewResult);
            resumePayload.remove("review_response");
            resumePayload.remove("poll_attempts");
            result.put("resume_payload", resumePayload);

            try {
                JsonFileUtil.saveToJsonFileAtPath(resumePayload, "resources/jsonfolder/output.json", true);
            } catch (Exception e) {
                log.warn("Could not save resume payload to output.json: {}", e.getMessage());
            }
        } catch (TimeoutException e) {
            log.error("Timed out polling review for record_id={} after {} minute(s)",
                    result.get("record_id"), batchResumeTimeoutMinutes, e);
            result.put("resume_error", "timeout after " + batchResumeTimeoutMinutes + " minutes");
        } catch (Exception e) {
            log.error("Text upload / review poll failed: {}", e.getMessage(), e);
            result.put("upload_error", e.getMessage());
        }
        return result;
    }

    /** Backward-compatible: upload text with no metadata. */
    public Map<String, Object> uploadTextAndAwaitResume(String text) {
        return uploadTextAndAwaitResume(text, null);
    }

    private String resolveRecordId(Map<String, Object> uploadResult, String fallbackBaseName) {
        String recordId = imagePdfUploadService.extractRecordId(uploadResult);
        if (recordId == null || recordId.isBlank()) {
            recordId = imagePdfUploadService.extractDocumentId(uploadResult);
        }
        if (recordId == null || recordId.isBlank()) {
            recordId = imagePdfUploadService.extractBatchId(uploadResult);
        }
        if (recordId == null || recordId.isBlank()) {
            recordId = fallbackBaseName;
            log.warn("Upload response had no record_id; using local id {}", recordId);
        }
        return recordId;
    }

    private void printUploadResponse(Map<String, Object> uploadResult) {
        printUploadResponse(uploadResult, "PDF");
    }

    private void printUploadResponse(Map<String, Object> uploadResult, String label) {
        try {
            String json = JsonFileUtil.toJsonString(uploadResult, true);
            log.info("Upload complete — response:\n{}", json);
            System.out.println("=== " + label + " upload response ===");
            System.out.println(json);
            System.out.println("===========================");
        } catch (Exception e) {
            log.info("Upload complete — response: {}", uploadResult);
            System.out.println("=== " + label + " upload response ===");
            System.out.println(uploadResult);
            System.out.println("===========================");
        }
    }
}
