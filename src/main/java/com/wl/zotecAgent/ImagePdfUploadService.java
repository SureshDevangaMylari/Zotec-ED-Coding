package com.wl.zotecAgent;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.wl.util.JsonFileUtil;

/**
 * Collects page images, merges them into one PDF, and uploads to the document processor.
 */
@Service
public class ImagePdfUploadService {

    private static final Logger log = LoggerFactory.getLogger(ImagePdfUploadService.class);

    private static final String PDF_OUTPUT_DIR = "resources/pdf";
    private static final String TEXT_OUTPUT_DIR = "resources/text";

    private final RestTemplate restTemplate;

    @Value("${document.upload.url}")
    private String uploadUrl;

    /** Same desk credentials as bot poll / Zotec login. */
    @Value("${bot.poll.desktop-id}")
    private String desktopId;

    @Value("${bot.poll.secret}")
    private String botSecret;

    public ImagePdfUploadService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public record PageImage(byte[] bytes, String fileName) {}

    public List<PageImage> collectImagesFromPage(Page page) throws InterruptedException {
        Locator imgs = page.locator("//img");
        long deadlineMs = System.currentTimeMillis() + 5 * 60 * 1000L; // wait up to 5 minutes

        log.info("Waiting for page image(s) to load (up to 5 minutes)...");
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                int count = imgs.count();
                if (count > 0) {
                    imgs.first().waitFor(new Locator.WaitForOptions()
                            .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                            .setTimeout(10_000));
                    log.info("At least one //img is visible (count={})", count);
                    break;
                }
            } catch (Exception e) {
                log.debug("Still waiting for //img: {}", e.getMessage());
            }
            Thread.sleep(2000);
        }

        if (imgs.count() == 0) {
            log.warn("No //img elements after initial wait — continuing to poll for decodeable images");
        }

        // Allow remaining images to finish rendering
        Thread.sleep(5000);

        List<PageImage> out = new ArrayList<>();
        while (System.currentTimeMillis() < deadlineMs) {
            out.clear();
            List<Locator> imgElements = imgs.all();
            log.info("Collecting {} image(s) for PDF", imgElements.size());

            for (int i = 0; i < imgElements.size(); i++) {
                try {
                    String src = String.valueOf(imgElements.get(i).getAttribute("src"));
                    if (src == null || src.isBlank() || "null".equals(src)) {
                        continue;
                    }
                    byte[] decoded = decodeBase64FromSrc(src);
                    if (decoded.length == 0) {
                        continue;
                    }
                    String ext = extensionFromSrc(src);
                    out.add(new PageImage(decoded, "page_" + (i + 1) + ext));
                } catch (Exception e) {
                    log.warn("Skipping image #{} for PDF: {}", i + 1, e.getMessage());
                }
            }

            if (!out.isEmpty()) {
                log.info("Collected {} decodeable image(s) for PDF", out.size());
                return out;
            }

            log.info("No decodeable images yet — waiting for UI to finish loading...");
            Thread.sleep(3000);
        }

        log.warn("Timed out after 5 minutes waiting for page images");
        return out;
    }

    public Path buildPdfFromImages(List<PageImage> images, String baseName) throws IOException {
        if (images == null || images.isEmpty()) {
            throw new IOException("No images to write to PDF");
        }
        Path dir = Path.of(PDF_OUTPUT_DIR);
        Files.createDirectories(dir);
        Path pdfPath = dir.resolve(baseName + ".pdf");

        try (PDDocument doc = new PDDocument()) {
            int pageNum = 0;
            for (PageImage img : images) {
                pageNum++;
                BufferedImage bim = ImageIO.read(new ByteArrayInputStream(img.bytes()));
                PDPage page;
                PDImageXObject pdImage;
                if (bim != null) {
                    pdImage = LosslessFactory.createFromImage(doc, bim);
                    page = new PDPage(new PDRectangle(bim.getWidth(), bim.getHeight()));
                } else {
                    pdImage = PDImageXObject.createFromByteArray(doc, img.bytes(), img.fileName());
                    page = new PDPage(PDRectangle.A4);
                }
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float w = page.getMediaBox().getWidth();
                    float h = page.getMediaBox().getHeight();
                    cs.drawImage(pdImage, 0, 0, w, h);
                }
                log.debug("PDF page {} from {}", pageNum, img.fileName());
            }
            doc.save(pdfPath.toFile());
        }

        log.info("Wrote PDF with {} page(s): {}", images.size(), pdfPath.toAbsolutePath().normalize());
        return pdfPath;
    }

    public Path saveTextFile(String text, String baseName) throws IOException {
        Path dir = Path.of(TEXT_OUTPUT_DIR);
        Files.createDirectories(dir);
        Path textPath = dir.resolve(baseName + ".txt");
        Files.writeString(textPath, text != null ? text : "");
        log.info("Wrote text file ({} chars): {}", text != null ? text.length() : 0,
                textPath.toAbsolutePath().normalize());
        return textPath;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> uploadPdf(Path pdfPath) {
        return uploadFile(pdfPath, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> uploadFile(Path filePath) {
        return uploadFile(filePath, null);
    }

    /**
     * POST multipart/form-data:
     * <ul>
     *   <li>{@code file} — PDF or text file (unchanged)</li>
     *   <li>{@code form_data} — JSON string of workfile well fields + {@code client_location} + {@code Batch Text}</li>
     * </ul>
     *
     * @param formData well summary + Select client(s) location (may be null)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> uploadFile(Path filePath, Map<String, Object> formData) {
        if (filePath == null || !Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("Upload file missing: " + filePath);
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(filePath.toFile()));

        String formDataJson = null;
        String formDataPretty = null;
        if (formData != null && !formData.isEmpty()) {
            try {
                formDataJson = JsonFileUtil.toJsonString(formData, false);
                formDataPretty = JsonFileUtil.toJsonString(formData, true);
                HttpHeaders jsonPartHeaders = new HttpHeaders();
                jsonPartHeaders.setContentType(MediaType.APPLICATION_JSON);
                body.add("form_data", new HttpEntity<>(formDataJson, jsonPartHeaders));
            } catch (Exception e) {
                log.warn("Could not serialize form_data JSON — uploading file only: {}", e.getMessage());
            }
        }

        logUploadPostRequest(filePath, formData, formDataPretty);

        HttpHeaders headers = DocumentProcessorAuth.uploadHeaders(desktopId, botSecret);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(uploadUrl, request, Map.class);
        Map<String, Object> respBody = response.getBody() != null ? response.getBody() : Map.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("http_status", response.getStatusCode().value());
        result.put("body", respBody);
        if (formData != null && !formData.isEmpty()) {
            result.put("form_data_sent", formData);
        }
        log.info("Upload response status={} body={}", response.getStatusCode().value(), respBody);
        return result;
    }

    /**
     * Logs each patient POST /upload: binary file part + form_data JSON (well + client_location + Batch Text).
     */
    private void logUploadPostRequest(Path filePath, Map<String, Object> formData, String formDataPretty) {
        long sizeBytes = -1L;
        try {
            sizeBytes = Files.size(filePath);
        } catch (Exception ignored) {
        }
        String fileName = filePath.getFileName().toString();
        String kind = fileName.toLowerCase(Locale.ROOT).endsWith(".txt") ? "text"
                : fileName.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "image/pdf" : "file";
        Object clientLocation = formData != null ? formData.get("client_location") : null;
        Object batchText = formData != null ? formData.get("Batch Text") : null;

        log.info("========== POST /upload (per patient) ==========");
        log.info("URL: {}", uploadUrl);
        log.info("Content-Type: multipart/form-data");
        log.info("Auth: {}={}, {}={}",
                DocumentProcessorAuth.DESKTOP_ID_HEADER, desktopId,
                DocumentProcessorAuth.BOT_SECRET_HEADER, DocumentProcessorAuth.maskSecret(botSecret));
        log.info("part 'file': name={}, type={}, path={}, size_bytes={}",
                fileName, kind, filePath.toAbsolutePath().normalize(), sizeBytes);
        log.info("part 'form_data' client_location: {}",
                clientLocation != null ? clientLocation : "(missing)");
        log.info("part 'form_data' Batch Text: {}",
                batchText != null ? batchText : "(missing)");
        if (formDataPretty != null && !formDataPretty.isBlank()) {
            log.info("part 'form_data' JSON:\n{}", formDataPretty);
        } else {
            log.warn("part 'form_data': (empty — file only)");
        }
        log.info("================================================");
    }

    @SuppressWarnings("unchecked")
    public String extractRecordId(Map<String, Object> uploadResult) {
        if (uploadResult == null) {
            return null;
        }
        Object bodyObj = uploadResult.get("body");
        if (!(bodyObj instanceof Map)) {
            return null;
        }
        return extractRecordIdFromMap((Map<String, Object>) bodyObj);
    }

    @SuppressWarnings("unchecked")
    private String extractRecordIdFromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        for (String key : List.of("record_id", "recordId", "document_id", "documentId", "id")) {
            Object v = map.get(key);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        Object data = map.get("data");
        if (data instanceof Map) {
            return extractRecordIdFromMap((Map<String, Object>) data);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public String extractDocumentId(Map<String, Object> uploadResult) {
        if (uploadResult == null) {
            return null;
        }
        Object bodyObj = uploadResult.get("body");
        if (!(bodyObj instanceof Map)) {
            return null;
        }
        return extractDocumentIdFromMap((Map<String, Object>) bodyObj);
    }

    /** @deprecated Prefer {@link #extractDocumentId}; kept for older upload response shapes. */
    @Deprecated
    @SuppressWarnings("unchecked")
    public String extractBatchId(Map<String, Object> uploadResult) {
        String documentId = extractDocumentId(uploadResult);
        if (documentId != null && !documentId.isBlank()) {
            return documentId;
        }
        if (uploadResult == null) {
            return null;
        }
        Object bodyObj = uploadResult.get("body");
        if (!(bodyObj instanceof Map)) {
            return null;
        }
        return extractBatchIdFromMap((Map<String, Object>) bodyObj);
    }

    @SuppressWarnings("unchecked")
    private String extractDocumentIdFromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        for (String key : List.of("document_id", "documentId")) {
            Object v = map.get(key);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        Object data = map.get("data");
        if (data instanceof Map) {
            return extractDocumentIdFromMap((Map<String, Object>) data);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractBatchIdFromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        for (String key : List.of("batch_id", "batchId", "batchid", "id", "job_id", "jobId", "task_id", "taskId")) {
            Object v = map.get(key);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        Object data = map.get("data");
        if (data instanceof Map) {
            return extractBatchIdFromMap((Map<String, Object>) data);
        }
        return null;
    }

    private static byte[] decodeBase64FromSrc(String src) {
        String base64 = src;
        if (src.startsWith("data:")) {
            int commaIdx = src.indexOf(',');
            if (commaIdx > 0) {
                base64 = src.substring(commaIdx + 1);
            }
        }
        return Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
    }

    private static String extensionFromSrc(String src) {
        if (src.startsWith("data:image/jpeg") || src.startsWith("data:image/jpg")) {
            return ".jpg";
        }
        if (src.startsWith("data:image/png")) {
            return ".png";
        }
        if (src.startsWith("data:image/gif")) {
            return ".gif";
        }
        if (src.startsWith("data:image/webp")) {
            return ".webp";
        }
        return ".png";
    }
}
