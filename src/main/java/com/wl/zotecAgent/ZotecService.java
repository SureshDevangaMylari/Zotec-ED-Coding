package com.wl.zotecAgent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.wl.util.JsonFileUtil;
import com.wl.util.PlaywrightService;
import wl.ai.ClinicalExtractionService;

/**
 * Zotec Service - Processes images from webpage using Playwright, extracts
 * patient data via local LLM, deep-merges all image results, and validates the
 * coding form against extracted data.
 * <p>
 * Validation rules (see validatePatientDetails):
 * <ul>
 * <li>encounter = patient ac num (Encounter # from
 * identifiers.account_number)</li>
 * <li>mrn there and name not = exception (search by MRN, select Not Listed if
 * not found)</li>
 * <li>choose demo not loaded when patient name not loaded</li>
 * <li>service location = batch text (from page table or first base64
 * image)</li>
 * <li>ED disposition: discharge/expired/deceased = do not click Admitted;
 * admitted/in hospital = click Yes</li>
 * <li>Providers: MD+PA → MD=Supervising, PA=Rendering; MD only → MD=Rendering,
 * Supervising blank</li>
 * </ul>
 */
@Service
public class ZotecService {

    private static final Logger log = LoggerFactory.getLogger(ZotecService.class);
    private final ClinicalExtractionService extractionService = new ClinicalExtractionService();
    private Map<String, Object> mergedData = new HashMap<>();

    private static final Set<String> SKIP_KEYS = Set.of("raw", "raw_text", "source_type", "text", "billing_notes");

    /**
     * Relative to JVM working directory (same pattern as
     * {@code resources/jsonfolder/output.json}).
     */
    private static final String BASE64_TEXT_OUTPUT_FILE = "resources/jsonfolder/base64ToText.txt";

    private static final String BASE64_IMAGE_OUTPUT_BASE = "resources/jsonfolder/base64Decoded";

    @Value("${zotec.portal.username}")
    private String portalUsername;

    @Value("${zotec.portal.password}")
    private String portalPassword;

    private enum DetectedImageKind {
	NONE, PNG, JPEG, GIF, WEBP
    }

    void login(Page page) throws Exception {
	if (portalUsername == null || portalUsername.isBlank() || portalPassword == null
		|| portalPassword.isBlank()) {
	    throw new IllegalStateException(
		    "zotec.portal.username / zotec.portal.password must be set in application.properties");
	}
	log.info("Logging into Zotec portal as {}", portalUsername);
	page.navigate("https://coding.zotecpartners.com/");
	PlaywrightService ps = new PlaywrightService(page);
	ps.fill(page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("E-Mail Address")),
		portalUsername, "entering username");
	ps.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Next")), "click next");
	ps.fill(page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")), portalPassword,
		"enter pass");

	ps.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Verify")), "click verify");

	Locator workfile = page.getByText("Coding Workfile");
	ps.click(workfile, "click coding workfle");
    }

    void validateForm() {

	List<String> list = Arrays.asList("27265", "99291");

    }

    /**
     * First img {@code src} with valid Base64: if the payload is a
     * PNG/JPEG/GIF/WebP, writes binary to
     * {@code resources/jsonfolder/base64Decoded.&lt;ext&gt;} and a short note to
     * {@value #BASE64_TEXT_OUTPUT_FILE}; otherwise writes UTF-8 text to the .txt
     * file.
     *
     * @return a short status line (saved image path or text), or null if nothing
     *         decoded
     */
    public String base64ToText(Page page) {
	PlaywrightService ps = new PlaywrightService(page);
	ps.waitForElement(page.locator("//img").first(), "waiting for ele");
	try {
	    Thread.sleep(3000);
	    List<Locator> imgElements = ps.getElements("//img", "getting img elements");
	    log.info("base64ToText: {} img element(s)", imgElements.size());
	    for (int i = 0; i < imgElements.size(); i++) {
		try {
		    String src = String.valueOf(imgElements.get(i).getAttribute("src"));
		    if (src == null || src.isBlank() || "null".equals(src)) {
			continue;
		    }
		    String base64 = src;
		    if (src.startsWith("data:")) {
			int commaIdx = src.indexOf(',');
			if (commaIdx > 0) {
			    base64 = src.substring(commaIdx + 1);
			}
		    }
		    byte[] decoded = decodeBase64PayloadToBytes(base64);
		    return writeDecodedPayload(decoded, src);
		} catch (IllegalArgumentException e) {
		    log.warn("base64ToText image #{}: invalid Base64 ({})", i + 1, e.getMessage());
		} catch (Exception e) {
		    log.warn("base64ToText image #{}: {}", i + 1, e.getMessage());
		}
	    }
	} catch (Exception e) {
	    log.error("base64ToText: {}", e.getMessage(), e);
	}
	return null;
    }

    /**
     * Decodes Base64 and writes either an image file (if bytes look like
     * PNG/JPEG/GIF/WebP) or UTF-8 text to {@value #BASE64_TEXT_OUTPUT_FILE}.
     */
    public String base64ToText(String base64) throws IOException {
	byte[] decoded = decodeBase64PayloadToBytes(base64);
	return writeDecodedPayload(decoded, null);
    }

    private static byte[] decodeBase64PayloadToBytes(String base64) {
	if (base64 == null || base64.isBlank()) {
	    return new byte[0];
	}
	String payload = base64.trim();
	int dataIdx = payload.indexOf("base64,");
	if (dataIdx >= 0) {
	    payload = payload.substring(dataIdx + "base64,".length());
	}
	return Base64.getDecoder().decode(payload.replaceAll("\\s", ""));
    }

    /**
     * @param dataUrlOptional full img src when available (e.g.
     *                        {@code data:image/png;base64,...}) for extension hint
     */
    private String writeDecodedPayload(byte[] decoded, String dataUrlOptional) throws IOException {
	if (decoded == null || decoded.length == 0) {
	    writeBase64TextFile("");
	    return "";
	}
	DetectedImageKind kind = detectImageKind(decoded);
	String extFromMime = extensionFromDataUrl(dataUrlOptional);
	if (kind != DetectedImageKind.NONE) {
	    String ext = extFromMime != null ? extFromMime : extensionForKind(kind);
	    Path out = Path.of(BASE64_IMAGE_OUTPUT_BASE + ext);
	    Files.createDirectories(out.getParent());
	    Files.write(out, decoded);
	    String note = "Binary image saved (not UTF-8 text): " + out.toAbsolutePath().normalize();
	    writeBase64TextFile(note);
	    log.info("Wrote {} byte(s) to {}", decoded.length, out.toAbsolutePath().normalize());
	    return note;
	}
	String text = new String(decoded, StandardCharsets.UTF_8);
	writeBase64TextFile(text);
	log.info("Wrote decoded UTF-8 text to {}", Path.of(BASE64_TEXT_OUTPUT_FILE).toAbsolutePath().normalize());
	return text;
    }

    private static DetectedImageKind detectImageKind(byte[] b) {
	if (b.length >= 8 && b[0] == (byte) 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
	    return DetectedImageKind.PNG;
	}
	if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
	    return DetectedImageKind.JPEG;
	}
	if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8' && (b[4] == '7' || b[4] == '9')
		&& b[5] == 'a') {
	    return DetectedImageKind.GIF;
	}
	if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E'
		&& b[10] == 'B' && b[11] == 'P') {
	    return DetectedImageKind.WEBP;
	}
	return DetectedImageKind.NONE;
    }

    private static String extensionForKind(DetectedImageKind k) {
	return switch (k) {
	case PNG -> ".png";
	case JPEG -> ".jpg";
	case GIF -> ".gif";
	case WEBP -> ".webp";
	default -> ".bin";
	};
    }

    /** e.g. data:image/png;base64, → .png */
    private static String extensionFromDataUrl(String src) {
	if (src == null || !src.startsWith("data:")) {
	    return null;
	}
	int semi = src.indexOf(';');
	if (semi <= 5) {
	    return null;
	}
	String mime = src.substring(5, semi).toLowerCase(Locale.ROOT);
	if (mime.contains("png")) {
	    return ".png";
	}
	if (mime.contains("jpeg") || mime.contains("jpg")) {
	    return ".jpg";
	}
	if (mime.contains("gif")) {
	    return ".gif";
	}
	if (mime.contains("webp")) {
	    return ".webp";
	}
	return null;
    }

    private void writeBase64TextFile(String text) {
	try {
	    Path path = Path.of(BASE64_TEXT_OUTPUT_FILE);
	    Files.createDirectories(path.getParent());
	    Files.writeString(path, text != null ? text : "", StandardCharsets.UTF_8);
	} catch (IOException e) {
	    log.error("Failed to write {}: {}", BASE64_TEXT_OUTPUT_FILE, e.getMessage());
	}
    }

    /**
     * Process all img elements on the page, extract clinical data from each base64
     * image, and deep-merge everything into a single combined map.
     *
     * @return Merged patient data map ready for CodingFormValidationService
     */
    public Map<String, Object> getDataFromImages(Page page) {
	mergedData = new HashMap<>();
	PlaywrightService ps = new PlaywrightService(page);
	int processedCount = 0;

	ps.waitForElement(page.locator("//img").first(), "waiting for ele");

	try {
	    Thread.sleep(3000);
	    extractBatchInfo(page, ps);

	    log.info("Starting to process all images from webpage...");
	    List<Locator> imgElements = ps.getElements("//img", "getting img elements");
	    log.info("Found {} img elements", imgElements.size());

	    for (int i = 0; i < imgElements.size(); i++) {
		try {
		    String src = String.valueOf(imgElements.get(i).getAttribute("src"));
		    if (src == null || src.trim().isEmpty() || "null".equals(src)) {
			log.warn("Image #{} has no src, skipping", i + 1);
			continue;
		    }

		    log.info("Processing image #{}: {}...", i + 1,
			    src.length() > 80 ? src.substring(0, 80) + "..." : src);

		    String mediaType = "image/png";
		    String base64 = src;
		    if (src.startsWith("data:")) {
			int commaIdx = src.indexOf(',');
			if (commaIdx > 0) {
			    String header = src.substring(5, src.indexOf(';'));
			    if (!header.isBlank())
				mediaType = header;
			    base64 = src.substring(commaIdx + 1);
			}
		    }

		    Map<String, Object> result = extractionService.extractFromBase64(base64, mediaType);
		    if (result != null && !result.isEmpty()) {
			deepMerge(mergedData, result);
			processedCount++;
			log.info("Image #{} extracted and merged successfully", (i + 1) +" out of "+imgElements.size());
		    } else {
			log.warn("No data extracted from image #{}", i + 1);
		    }
		} catch (Exception e) {
		    log.error("Error processing image #{}: {}", i + 1, e.getMessage(), e);
		}
	    }

	    mergedData.put("total_images_found", imgElements.size());
	    mergedData.put("total_processed", processedCount);
	    mergedData.put("processing_date", java.time.LocalDateTime.now().toString());

	    log.info("Completed: {} images found, {} processed", imgElements.size(), processedCount);

	    try {
		JsonFileUtil.saveToJsonFileAtPath(mergedData, "resources/jsonfolder/output.json", true);
	    } catch (Exception e) {
		log.error("Failed to save JSON: {}", e.getMessage());
	    }

//            validatePatientDetails(page);

	} catch (Exception e) {
	    log.error("Error in getDataFromImages: {}", e.getMessage(), e);
	    mergedData.put("error", e.getMessage());
	}

	return mergedData;
    }

    /**
     * Validates extracted data against the coding form and updates form fields when
     * extracted data does not match. Call this after
     * {@link #getDataFromImages(Page)}.
     *
     * Rules applied: - encounter = patient ac num (Encounter # = account_number) -
     * mrn present and name not = exception (search by MRN, select Not Listed) - do
     * not load demo when patient name not loaded - service location = batch text
     * (from batch_info or first image) - ED disposition: discharge/expired = don't
     * click Admitted; admitted = click Yes - Providers: MD+PA → MD=Supervising,
     * PA=Rendering; MD only → MD=Rendering
     */
    public void validatePatientDetails(Page page) {
	validatePatientDetails(page, mergedData);
    }

    /**
     * Validates extracted data against the coding form and updates form fields. Use
     * this when you have custom merged data (e.g. from a prior getDataFromImages
     * call).
     */
    public void validatePatientDetails(Page page, Map<String, Object> extractedData) {
	if (extractedData == null || extractedData.isEmpty()) {
	    log.warn("No extracted data to validate; skipping validatePatientDetails");
	    return;
	}
	prepareDataForValidation(extractedData);
	CodingFormValidationService cv = new CodingFormValidationService(page);
	cv.validateAndUpdate(extractedData);
	log.info("validatePatientDetails completed");
    }

    /**
     * Prepares merged data per business rules before form validation. Rules: -
     * service location = batch text (batch_info.batch_text, or from first base64
     * image) - encounter = patient account number (identifiers.account_number)
     */
    @SuppressWarnings("unchecked")
    private void prepareDataForValidation(Map<String, Object> data) {
	Map<String, Object> batchInfo = data.get("batch_info") instanceof Map
		? (Map<String, Object>) data.get("batch_info")
		: null;
	Map<String, Object> coding = data.get("coding") instanceof Map ? (Map<String, Object>) data.get("coding")
		: null;
	if (coding == null) {
	    coding = new LinkedHashMap<>();
	    data.put("coding", coding);
	}
	String batchText = batchInfo != null ? safeStr(batchInfo, "batch_text") : null;
	if (batchText == null || batchText.isBlank()) {
	    Object topLevel = data.get("batch_text");
	    if (topLevel instanceof String && !((String) topLevel).isBlank()) {
		batchText = (String) topLevel;
		log.info("Using batch_text from extracted data (first base64 image)");
	    }
	}
	String serviceLocation = safeStr(coding, "service_location");
	if ((serviceLocation == null || serviceLocation.isBlank()) && batchText != null && !batchText.isBlank()) {
	    coding.put("service_location", batchText);
	    log.info("Set service_location from batch_text: {}", batchText);
	}
    }

    private String safeStr(Map<String, Object> map, String key) {
	if (map == null)
	    return null;
	Object v = map.get(key);
	if (v == null)
	    return null;
	String s = String.valueOf(v).trim();
	return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    /**
     * Returns the last merged data from {@link #getDataFromImages(Page)}.
     */
    public Map<String, Object> getMergedData() {
	return new HashMap<>(mergedData);
    }

    public void clearData() {
	mergedData.clear();
	log.info("Cleared all patient data");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BATCH INFO — extract th/td key-value pairs from the info table
    // ═══════════════════════════════════════════════════════════════════════════

    private void extractBatchInfo(Page page, PlaywrightService ps) {
	try {
	    log.info("Extracting batch info table (th/td pairs)...");
	    Map<String, Object> batchInfo = new LinkedHashMap<>();

	    List<Locator> rows = page.locator("table.table-no-borders tr").all();
	    for (Locator row : rows) {
		List<Locator> ths = row.locator("th").all();
		List<Locator> tds = row.locator("td").all();

		for (int j = 0; j < Math.min(ths.size(), tds.size()); j++) {
		    String key = ths.get(j).innerText().trim();
		    String value = tds.get(j).innerText().trim();

		    if (key.isEmpty())
			continue;

		    String normalizedKey = key.toLowerCase().replace(" ", "_");
		    if (!value.isEmpty() && !"Unknown".equalsIgnoreCase(value)) {
			batchInfo.put(normalizedKey, value);
		    }
		}
	    }

	    if (!batchInfo.isEmpty()) {
		mergedData.put("batch_info", batchInfo);
		log.info("Batch info extracted: {}", batchInfo);
	    }
	} catch (Exception e) {
	    log.warn("Could not extract batch info table: {}", e.getMessage());
	}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEEP MERGE — combines two maps without overwriting with nulls
    // ═══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
	for (Map.Entry<String, Object> entry : source.entrySet()) {
	    String key = entry.getKey();
	    Object newVal = entry.getValue();

	    if (SKIP_KEYS.contains(key))
		continue;
	    if (isNullOrEmpty(newVal))
		continue;

	    Object existing = target.get(key);

	    if (existing == null) {
		target.put(key, newVal);
	    } else if (existing instanceof Map && newVal instanceof Map) {
		deepMerge((Map<String, Object>) existing, (Map<String, Object>) newVal);
	    } else if (existing instanceof List && newVal instanceof List) {
		mergeArrays((List<Object>) existing, (List<Object>) newVal);
	    } else if (existing instanceof List && !(newVal instanceof List)) {
		addIfAbsent((List<Object>) existing, newVal);
	    } else if (!(existing instanceof List) && newVal instanceof List) {
		List<Object> merged = new ArrayList<>();
		merged.add(existing);
		mergeArrays(merged, (List<Object>) newVal);
		target.put(key, merged);
	    } else if (!existing.equals(newVal)) {
		target.put(key, newVal);
	    }
	}
    }

    @SuppressWarnings("unchecked")
    private void mergeArrays(List<Object> target, List<Object> source) {
	for (Object item : source) {
	    if (isNullOrEmpty(item))
		continue;

	    if (item instanceof Map) {
		Map<String, Object> itemMap = (Map<String, Object>) item;
		Map<String, Object> match = findMatchingMapEntry(target, itemMap);
		if (match != null) {
		    deepMerge(match, itemMap);
		} else {
		    target.add(item);
		}
	    } else {
		addIfAbsent(target, item);
	    }
	}
    }

    /**
     * Finds an existing entry in a list of maps that matches the candidate on a
     * known identity key (code, cpt_code, name, study).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findMatchingMapEntry(List<Object> list, Map<String, Object> candidate) {
	String[] matchKeys = { "code", "cpt_code", "name", "study" };
	for (Object existing : list) {
	    if (!(existing instanceof Map))
		continue;
	    Map<String, Object> existingMap = (Map<String, Object>) existing;
	    for (String mk : matchKeys) {
		Object a = existingMap.get(mk);
		Object b = candidate.get(mk);
		if (a != null && b != null && a.equals(b))
		    return existingMap;
	    }
	}
	return null;
    }

    private void addIfAbsent(List<Object> list, Object item) {
	if (!list.contains(item))
	    list.add(item);
    }

    private boolean isNullOrEmpty(Object val) {
	if (val == null)
	    return true;
	if (val instanceof String s)
	    return s.isBlank() || "null".equalsIgnoreCase(s);
	if (val instanceof List<?> l)
	    return l.isEmpty();
	if (val instanceof Map<?, ?> m)
	    return m.isEmpty();
	return false;
    }
}
