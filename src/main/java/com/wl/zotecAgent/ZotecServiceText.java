package com.wl.zotecAgent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
 
import com.wl.util.JsonFileUtil;
import com.wl.util.PlaywrightService;
import wl.ai.ClinicalExtractionService;

/**
 * Zotec Service - Processes images from webpage using Playwright and extracts
 * patient data
 */
@Service
public class ZotecServiceText {

    private static final Logger log = LoggerFactory.getLogger(ZotecServiceText.class);
    private Map<String, Object> patientDataMap = new HashMap<>();

    private PlaywrightService playwrightServices;

    /**
     * Update info method that accepts map with string, object and pushes key values
     * into the Zotec coding form.
     * 
     * @param data Map containing update information
     * @param page Current Playwright page pointing at the coding form
     */
    public void updateInfo(Map<String, Object> data, Page page) {
	try {
	    log.info("Updating info with data: {}", data == null ? "null" : data.keySet());

	    // Persist the raw data for later use
	    if (data != null) {
		patientDataMap.putAll(data);
		log.info("Successfully updated info. Total entries: {}", patientDataMap.size());
	    }

	    // If we have a live page, push key values into the HTML form
	    if (data != null && page != null) {
		PlaywrightService ps = new PlaywrightService(page);

		// 1) encounter.service_date -> DOS field (#dateOfService)
		try {
		    @SuppressWarnings("unchecked")
		    Map<String, Object> encounter = (Map<String, Object>) data.get("encounter");
		    if (encounter != null && encounter.get("service_date") != null) {
			String rawServiceDate = String.valueOf(encounter.get("service_date")).trim();
			String dos = formatServiceDate(rawServiceDate);
			if (dos != null && !dos.isEmpty()) {
			    log.info("Setting Date of Service (DOS) to {}", dos);
			    ps.fill(page.locator("#dateOfService"), dos, "Date of Service");
			}
		    }
		} catch (Exception e) {
		    log.warn("Unable to update DOS field from encounter.service_date: {}", e.getMessage());
		}

		// 2) encounter.admit_date -> Admitted "Yes" button (if admit date present)
		try {
		    @SuppressWarnings("unchecked")
		    Map<String, Object> encounter = (Map<String, Object>) data.get("encounter");
		    if (encounter != null && encounter.get("admit_date") != null) {
			String admitDateRaw = String.valueOf(encounter.get("admit_date")).trim();
			if (!admitDateRaw.isEmpty()) {
			    log.info("Setting Admitted = Yes based on encounter.admit_date {}", admitDateRaw);
			    Locator admittedYesButton = page
				    .locator("div.form-group:has(label:has-text('Admitted')) button:has-text('Yes')");
			    if (admittedYesButton.count() > 0) {
				ps.click(admittedYesButton.first(), "Admitted - Yes");
			    }
			}
		    }
		} catch (Exception e) {
		    log.warn("Unable to set Admitted status from encounter.admit_date: {}", e.getMessage());
		}

		// 3) billing.accident_date -> Illness Date (#illnessDate), when present
		try {
		    @SuppressWarnings("unchecked")
		    Map<String, Object> billing = (Map<String, Object>) data.get("billing");
		    if (billing != null && billing.get("accident_date") != null) {
			String rawAccidentDate = String.valueOf(billing.get("accident_date")).trim();
			String illnessDate = formatServiceDate(rawAccidentDate);
			if (illnessDate != null && !illnessDate.isEmpty()) {
			    log.info("Setting Illness Date to {}", illnessDate);
			    ps.fill(page.locator("#illnessDate"), illnessDate, "Illness Date");
			}
		    } else {
			// Fallback: if no explicit accident_date, default illness date to service_date
			@SuppressWarnings("unchecked")
			Map<String, Object> encounter = (Map<String, Object>) data.get("encounter");
			if (encounter != null && encounter.get("service_date") != null) {
			    String rawServiceDate = String.valueOf(encounter.get("service_date")).trim();
			    String illnessDate = formatServiceDate(rawServiceDate);
			    if (illnessDate != null && !illnessDate.isEmpty()) {
				log.info("Setting Illness Date (fallback) to {}", illnessDate);
				ps.fill(page.locator("#illnessDate"), illnessDate, "Illness Date");
			    }
			}
		    }
		} catch (Exception e) {
		    log.warn("Unable to update Illness Date field from billing.accident_date: {}", e.getMessage());
		}

		// 4) Rendering Provider -> use last-line signature name when available
		try {
		    @SuppressWarnings("unchecked")
		    Map<String, Object> providerSignature = (Map<String, Object>) data.get("provider_signature");
		    String renderingName = null;

		    if (providerSignature != null && providerSignature.get("provider") != null) {
			// Prefer the signature line name (last line of the report)
			renderingName = String.valueOf(providerSignature.get("provider")).trim();
		    } else {
			// Fallback to structured provider.primary_provider if signature is missing
			Map<String, Object> provider = (Map<String, Object>) data.get("provider");
			if (provider != null && provider.get("primary_provider") != null) {
			    renderingName = String.valueOf(provider.get("primary_provider")).trim();
			}
		    }

		    if (renderingName != null && !renderingName.isEmpty()) {
			if (!renderingName.isEmpty()) {
			    log.info("Setting Rendering Provider to {}", renderingName);
			    Locator container = page.locator("#s2id_renderingprovider .select2-choice");
			    if (container.count() > 0) {
				ps.click(container.first(), "Rendering Provider dropdown");
				Locator searchInput = page
					.locator(".select2-drop-active .select2-search input.select2-input");
				searchInput.fill(renderingName);
				searchInput.press("Enter");
			    }
			}
		    }
		} catch (Exception e) {
		    log.warn("Unable to update Rendering Provider from provider.primary_provider: {}", e.getMessage());
		}
	    }
	} catch (Exception e) {
	    log.error("Error updating info: {}", e.getMessage(), e);
	}
    }

    /**
     * Get data from all images on webpage using Playwright Finds img tags using
     * getElements, extracts src attributes, processes each with SmartBase64Reader
     * 
     * @return Combined map of all patient data
     */
    public Map<String, Object> getDataFromText(Page page) {
	Map<String, Object> allPatientsData = new HashMap<>();
	ClinicalExtractionService extractionService = new ClinicalExtractionService();

	PlaywrightService ps = new PlaywrightService(page);
	try {
	    log.info("Starting to process all images from webpage using Playwright...");

	    // Loop through each img element and extract src
	    try {
		// Extract src attribute from img element

		// Call SmartBase64Reader.readImageToJson(src) for each src
		String text = ps.getText(page.locator("//*[@id='dictated-report-text']"), "gettign text");
		System.out.println(text);

		System.out.println("=== Processing text with default settings ===");
		Map<String, Object> result = extractionService.extract(text);
		System.out.println("Result: " + result);

		if (result != null && !result.isEmpty()) {
		    // Remove text and billing_notes values as requested

		    // Add this entire map to patientsMap using putAll

		    log.info("Successfully processed image {} - Patient: {}");
		} else {
		    log.warn("No data extracted from image source #{}");
		}

	    } catch (Exception e) {
		log.error("Error processing image src #{}: {}", e.getMessage(), e);

		// Add error record
		Map<String, Object> errorRecord = new HashMap<>();
		errorRecord.put("source_type", "error");
		errorRecord.put("error", e.getMessage());
		errorRecord.put("processed_date", java.time.LocalDateTime.now().toString());
	    }

	    // After loop done, create final combined object

	    log.info("Completed processing");

	    // Save to JSON file once all done
	    try {
		JsonFileUtil.saveToJsonFileAtPath(allPatientsData, "resources/jsonfolder/output.json", true);
		log.info("Successfully saved combined data to output.json");
	    } catch (Exception e) {
		log.error("Failed to save JSON file: {}", e.getMessage(), e);
	    }

	} catch (Exception e) {
	    log.error("Error in getDataFromImages: {}", e.getMessage(), e);
	    allPatientsData.put("error", "");
	}

	return allPatientsData;
    }

    /**
     * Remove text and billing_notes from the result map as requested
     * 
     * @param result Original result map
     * @return Cleaned result map without text and billing_notes
     */
    private Map<String, Object> cleanResult(Map<String, Object> result) {
	Map<String, Object> cleaned = new HashMap<>(result);

	// Remove text and billing_notes values as requested
	cleaned.remove("text");
	cleaned.remove("billing_notes");

	return cleaned;
    }

    /**
     * Extract patient key from patientIdentification.name
     * 
     * @param result Patient data map
     * @return Patient name from patientIdentification.name
     */
    private String getPatientKey(Map<String, Object> result) {
	try {
	    // Get patientIdentification.name as requested
	    if (result.containsKey("patientIdentification")) {
		@SuppressWarnings("unchecked")
		Map<String, Object> patientId = (Map<String, Object>) result.get("patientIdentification");
		if (patientId != null && patientId.containsKey("name")) {
		    return String.valueOf(patientId.get("name"));
		}
	    }

	    // Fallback: try other common name fields
	    if (result.containsKey("patient_name")) {
		return String.valueOf(result.get("patient_name"));
	    }

	    // Last fallback: use MRN if available
	    if (result.containsKey("patientIdentification")) {
		@SuppressWarnings("unchecked")
		Map<String, Object> patientId = (Map<String, Object>) result.get("patientIdentification");
		if (patientId != null && patientId.containsKey("mrn")) {
		    return "MRN-" + String.valueOf(patientId.get("mrn"));
		}
	    }

	} catch (Exception e) {
	    log.warn("Error extracting patient key: {}", e.getMessage());
	}

	return "Unknown-" + System.currentTimeMillis();
    }

    /**
     * Get current patient data map
     * 
     * @return Current patient data
     */
    public Map<String, Object> getPatientDataMap() {
	return new HashMap<>(patientDataMap);
    }

    /**
     * Clear all patient data
     */
    public void clearPatientData() {
	patientDataMap.clear();
	log.info("Cleared all patient data");
    }

    /**
     * Get the serializable patient data object This method can be called to
     * retrieve the serializable object for external use.
     * 
     * For the text-based service we currently do not aggregate into a
     * SerializablePatientData model, so this returns null and is kept only for API
     * symmetry with {@link ZotecService}.
     */
    public SerializablePatientData getSerializablePatientData() {
	log.info("getSerializablePatientData() called on ZotecServiceText - returning null");
	return null;
    }

    /**
     * Normalize a raw date string (for example "2/7/2026 7:31 PM" or "2/7/2026")
     * into the M/d/yyyy format expected by the Angular datepicker fields used for
     * DOS and Illness Date.
     */
    private String formatServiceDate(String raw) {
	if (raw == null || raw.isBlank()) {
	    return null;
	}

	String trimmed = raw.trim();

	// Try full datetime with time component first
	try {
	    DateTimeFormatter inputDateTime = DateTimeFormatter.ofPattern("M/d/yyyy h:mm a");
	    LocalDateTime dateTime = LocalDateTime.parse(trimmed, inputDateTime);
	    return dateTime.toLocalDate().format(DateTimeFormatter.ofPattern("M/d/yyyy"));
	} catch (DateTimeParseException ignored) {
	    // fall back to date-only pattern
	}

	// Fallback: date only
	try {
	    DateTimeFormatter inputDate = DateTimeFormatter.ofPattern("M/d/yyyy");
	    LocalDate date = LocalDate.parse(trimmed, inputDate);
	    return date.format(DateTimeFormatter.ofPattern("M/d/yyyy"));
	} catch (DateTimeParseException e) {
	    log.warn("Could not parse date '{}': {}", raw, e.getMessage());
	    return null;
	}
    }
}
