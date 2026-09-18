package com.wl.zotecAgent;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.SelectOption;
import com.wl.util.PlaywrightService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static com.wl.zotecAgent.FormSelectors.*;

/**
 * Validates and updates the Zotec coding form. Compares page values with
 * extracted data; only updates when they differ.
 */
public class CodingFormValidationService {

    private static final Logger log = LogManager.getLogger(CodingFormValidationService.class);

    private static final DateTimeFormatter[] DATE_PARSERS = { DateTimeFormatter.ofPattern("M/d/yyyy h:mm a"),
	    DateTimeFormatter.ofPattern("M/d/yyyy"), DateTimeFormatter.ofPattern("M/d/yy"),
	    DateTimeFormatter.ofPattern("MM/dd/yyyy"), DateTimeFormatter.ofPattern("MM/dd/yy"),
	    DateTimeFormatter.ofPattern("yyyy-MM-dd"), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
	    DateTimeFormatter.ofPattern("MM-dd-yyyy"), };
    private static final DateTimeFormatter FORM_DATE_FMT = DateTimeFormatter.ofPattern("M/d/yyyy");

    private final Page page;
    private final PlaywrightService ps;

    public CodingFormValidationService(Page page) {
	this.page = page;
	this.ps = new PlaywrightService(page);
    }

    public void validateAndUpdate(Map<String, Object> extractedData) {
	if (extractedData == null || extractedData.isEmpty())
	    return;
	log.info("Starting coding form validation");
	PlayTestActionLog.step("validatePatientDetails — coding form");
	sleep(200);
	updatePatient(extractedData);
	updateProfile(extractedData);
	updateEncounterNumber(extractedData);
	// Header Service Location / POS only — never CPT-row controls (those fill in validateCPT after ED)
	updateServiceLocation(extractedData);
	updatePlaceOfService(extractedData);
	updateDepartment(extractedData);
	updateDateOfService(extractedData);
	updateIllnessDate(extractedData);
	updateAdmitted(extractedData);
	updateProviders(extractedData);
	updateDisposition(extractedData);
	// ICD codes and accident date/type are filled later by Flow/FlowText:
	// ED → CPT (incl. row SL/POS) → validateICD → updateBillingExtras.
	log.info("Coding form validation complete");
    }

    /**
     * Fills billing_type / accident_date / accident_type / critical_care when those
     * controls exist on the form. Call after ICD — accident fields often appear only
     * after an accident diagnosis is selected.
     */
    public void updateBillingExtras(Map<String, Object> data) {
	if (data == null || data.isEmpty())
	    return;
	waitForAccidentFieldsIfNeeded(data);
	updateBillingType(data);
	updateAccidentDate(data);
	updateAccidentType(data);
	updateCriticalCare(data);
    }

    /**
     * Accident Date/Type may take a moment to render after an accident ICD is added.
     * Poll briefly when JSON has accident data but fields are not yet present.
     */
    private void waitForAccidentFieldsIfNeeded(Map<String, Object> data) {
	String rawDate = firstNonBlank(strTop(data, "accident_date"),
		safeStr(asMap(data.get("billing")), "accident_date"));
	String rawType = firstNonBlank(strTop(data, "accident_type"),
		safeStr(asMap(data.get("billing")), "accident_type"),
		safeStr(asMap(data.get("billing")), "accident_code"));
	if ((rawDate == null || rawDate.isBlank()) && (rawType == null || rawType.isBlank())) {
	    return;
	}
	for (int i = 0; i < 15; i++) {
	    boolean dateReady = fieldPresent(ACCIDENT_DATE);
	    boolean typeReady = fieldPresent(ACCIDENT_TYPE_CHOICE) || fieldPresent(ACCIDENT_TYPE);
	    if (dateReady || typeReady) {
		log.info("Accident fields visible after {} wait(s) (date={}, type={})", i, dateReady, typeReady);
		return;
	    }
	    sleep(400);
	}
	log.warn("Accident Date/Type still not on form after wait — updateBillingExtras will skip if absent");
    }

    private void updatePatient(Map<String, Object> data) {
	try {
	    Map<String, Object> patient = asMap(data.get("patient"));
	    Map<String, Object> identifiers = asMap(data.get("identifiers"));
	    String patientName = firstNonBlank(safeStr(patient, "name"), safeStr(patient, "patient_name"));
	    String mrn = firstNonBlank(safeStr(identifiers, "mrn"), safeStr(patient, "mrn"));

	    if (patientName == null && mrn == null) {
		PlayTestActionLog.noData("Patient", "no name or MRN in payload");
		return;
	    }

	    // Patient Select2 absent on patient-encounter batches — skip quietly
	    if (!fieldPresent(PATIENT_CHOICE)) {
		PlayTestActionLog.skip("Patient", "field not on form (e.g. patient-encounter batch)");
		return;
	    }

	    dismissSelect2();
	    String current = getPageText(PATIENT_CHOSEN);
	    String uiMrn = readUiMrn();
	    log.info("Patient current value: {} | UI MRN: {}", current, uiMrn);

	    boolean nameMatches = patientName != null && !patientName.isBlank() && valuesMatch(current, patientName);
	    boolean mrnNeedsResearch = mrn != null && !mrn.isBlank() && uiMrnUnknownOrMismatch(uiMrn, mrn);

	    if (nameMatches && !mrnNeedsResearch) {
		log.info("Patient already '{}' with matching MRN, skipping", current);
		PlayTestActionLog.skip("Patient", current, patientName);
		return;
	    }

	    // Name matches but MRN is Unknown / wrong — re-select via MRN
	    if (nameMatches && mrnNeedsResearch) {
		log.info("Patient name matches '{}' but UI MRN '{}' ≠ JSON '{}' — re-search by MRN",
			current, uiMrn, mrn);
		PlayTestActionLog.update("Patient",
			"name ok but MRN '" + uiMrn + "' -> search MRN '" + mrn + "'");
		fillPatientByMrn(mrn);
		return;
	    }

	    if (patientName != null && !patientName.isBlank()) {
		PlayTestActionLog.update("Patient", "setting to '" + patientName + "' (was '" + current + "')");
		if (!typeIntoSelect2(PATIENT_CHOICE, patientName, "Patient")) {
		    PlayTestActionLog.skip("Patient", "could not open Select2");
		    return;
		}
		sleep(600);
		if (!selectFirstSelect2Result()) {
		    if (mrn != null && !mrn.isBlank()) {
			fillPatientByMrn(mrn);
		    }
		} else if (mrn != null && !mrn.isBlank()) {
		    // After name pick, if MRN still wrong/unknown, retry by MRN
		    sleep(300);
		    String afterMrn = readUiMrn();
		    if (uiMrnUnknownOrMismatch(afterMrn, mrn)) {
			log.info("After name select UI MRN '{}' still bad — re-search by MRN '{}'", afterMrn, mrn);
			fillPatientByMrn(mrn);
		    }
		}
	    } else if (mrn != null && !mrn.isBlank()) {
		PlayTestActionLog.update("Patient", "search by MRN '" + mrn + "'");
		fillPatientByMrn(mrn);
	    }
	} catch (Exception e) {
	    log.warn("Unable to update Patient: {}", e.getMessage());
	}
    }

    /** Reads the static MRN text shown under Patient (may be "Unknown"). */
    private String readUiMrn() {
	try {
	    Locator loc = page.locator(MRN_STATIC).first();
	    if (loc.count() == 0) {
		return null;
	    }
	    String t = loc.innerText();
	    if (t == null) {
		return null;
	    }
	    t = t.replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
	    return t.isEmpty() ? null : t;
	} catch (Exception e) {
	    return null;
	}
    }

    /** True when UI MRN is blank/Unknown or does not contain the expected MRN. */
    private boolean uiMrnUnknownOrMismatch(String uiMrn, String expectedMrn) {
	if (expectedMrn == null || expectedMrn.isBlank()) {
	    return false;
	}
	if (uiMrn == null || uiMrn.isBlank()) {
	    return true;
	}
	String u = uiMrn.trim();
	if ("unknown".equalsIgnoreCase(u) || "n/a".equalsIgnoreCase(u) || "-".equals(u)
		|| "none".equalsIgnoreCase(u)) {
	    return true;
	}
	String digitsUi = u.replaceAll("\\D", "");
	String digitsExp = expectedMrn.replaceAll("\\D", "");
	if (!digitsExp.isEmpty() && !digitsUi.isEmpty()) {
	    return !digitsUi.contains(digitsExp) && !digitsExp.contains(digitsUi);
	}
	return !u.toLowerCase().contains(expectedMrn.trim().toLowerCase());
    }

    /**
     * Sets Profile (IB / SP / WC) from {@code patient.profile_code}. Prefers native
     * {@code #profileId} so Angular {@code profileChanged()} runs; falls back to Select2.
     */
    private void updateProfile(Map<String, Object> data) {
	try {
	    Map<String, Object> patient = asMap(data.get("patient"));
	    String profile = firstNonBlank(safeStr(patient, "profile_code"), safeStr(patient, "profile"),
		    safeStr(patient, "profileId"));
	    if (profile == null || profile.isBlank()) {
		PlayTestActionLog.noData("Profile");
		return;
	    }
	    profile = profile.trim().toUpperCase();
	    // Accept full labels too
	    if (profile.startsWith("SELF")) {
		profile = "SP";
	    } else if (profile.startsWith("INSUR")) {
		profile = "IB";
	    } else if (profile.startsWith("WORK")) {
		profile = "WC";
	    }

	    Locator nativeSelect = page.locator(PROFILE_SELECT).first();
	    String current = "";
	    try {
		if (nativeSelect.count() > 0) {
		    current = nativeSelect.inputValue();
		}
	    } catch (Exception ignored) {
	    }
	    if (current == null || current.isBlank()) {
		current = getPageText(PROFILE_CHOSEN);
	    }
	    if (current != null && profile.equalsIgnoreCase(current.trim())) {
		PlayTestActionLog.skip("Profile", current, profile);
		log.info("Profile already '{}', skipping", current);
		return;
	    }

	    PlayTestActionLog.update("Profile", "'" + current + "' -> '" + profile + "'");
	    log.info("Setting Profile to '{}' (was '{}')", profile, current);

	    if (nativeSelect.count() > 0) {
		try {
		    nativeSelect.selectOption(new SelectOption().setValue(profile));
		    sleep(150);
		    page.evaluate("(code) => {"
			    + "  const sel = document.querySelector('#profileId') || document.querySelector(\"select[name='profileId']\");"
			    + "  if (!sel) return;"
			    + "  sel.value = code;"
			    + "  sel.dispatchEvent(new Event('input', { bubbles: true }));"
			    + "  sel.dispatchEvent(new Event('change', { bubbles: true }));"
			    + "  const $ = window.jQuery || window.$;"
			    + "  if ($ && $.fn) { try { $(sel).val(code).trigger('change'); } catch (e) {} }"
			    + "  const ang = window.angular;"
			    + "  if (ang) {"
			    + "    try {"
			    + "      const scope = ang.element(sel).scope();"
			    + "      if (scope) {"
			    + "        scope.form = scope.form || {};"
			    + "        scope.form.profileId = code;"
			    + "        if (scope.profileChanged) scope.profileChanged();"
			    + "        if (scope.$apply) scope.$apply();"
			    + "      }"
			    + "    } catch (e2) {}"
			    + "  }"
			    + "  const chosen = document.querySelector('#s2id_profileId .select2-chosen');"
			    + "  if (chosen) chosen.textContent = code;"
			    + "}", profile);
		    sleep(200);
		    String after = "";
		    try {
			after = nativeSelect.inputValue();
		    } catch (Exception ignored) {
		    }
		    if (profile.equalsIgnoreCase(after != null ? after.trim() : "")) {
			log.info("Profile set via native select to '{}'", after);
			return;
		    }
		} catch (Exception e) {
		    log.warn("Profile native select failed: {} — trying Select2", e.getMessage());
		}
	    }

	    if (!fieldPresent(PROFILE_CHOICE)) {
		PlayTestActionLog.skip("Profile", "field not on form");
		return;
	    }
	    dismissSelect2();
	    Locator choice = page.locator(PROFILE_CHOICE).first();
	    choice.click(new Locator.ClickOptions().setForce(true));
	    sleep(200);
	    Locator option = page.locator(
		    ".select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable")
		    .filter(new Locator.FilterOptions().setHasText(profile));
	    if (option.count() > 0) {
		option.first().click(new Locator.ClickOptions().setForce(true));
	    } else if (!selectFirstSelect2Result()) {
		dismissSelect2();
		PlayTestActionLog.skip("Profile", "no Select2 option for '" + profile + "'");
		return;
	    }
	    sleep(200);
	} catch (Exception e) {
	    log.warn("Unable to update Profile: {}", e.getMessage());
	    dismissSelect2();
	}
    }

    private void fillPatientByMrn(String mrn) {
	log.info("Entering MRN '{}' in Patient field, will click first result", mrn);
	if (!typeIntoSelect2(PATIENT_CHOICE, mrn, "Patient by MRN")) {
	    PlayTestActionLog.skip("Patient by MRN", "Select2 not available");
	    return;
	}
	sleep(900);
	if (!selectFirstSelect2Result()) {
	    log.info("MRN '{}' not found, selecting Not Listed", mrn);
	    Locator notListed = page.locator(NOT_LISTED);
	    if (notListed.count() > 0) {
		notListed.first().click(new Locator.ClickOptions().setForce(true));
		sleep(350);
	    } else {
		dismissSelect2();
		PlayTestActionLog.skip("Patient by MRN", "MRN not in list and Not Listed unavailable");
	    }
	}
    }

    private void updateEncounterNumber(Map<String, Object> data) {
	try {
	    Map<String, Object> identifiers = asMap(data.get("identifiers"));
	    String accountNumber = firstNonBlank(safeStr(identifiers, "account_number"),
		    safeStr(identifiers, "encounter_number"), safeStr(identifiers, "patient_id"));

	    if (accountNumber == null || accountNumber.isBlank()) {
		PlayTestActionLog.noData("Encounter #");
		return;
	    }

	    dismissSelect2();

	    boolean hasSelect2 = fieldPresent(ENCOUNTER_CHOICE);
	    Locator select2Choice = page.locator(ENCOUNTER_CHOICE);

	    if (!hasSelect2) {
		Locator textInput = firstVisibleEncounterTextInput();
		if (textInput == null) {
		    PlayTestActionLog.skip("Encounter #", "field not on form");
		    return;
		}
		String current = "";
		try {
		    current = textInput.inputValue();
		} catch (Exception ignored) {
		}
		if (valuesMatch(current, accountNumber)) {
		    PlayTestActionLog.skip("Encounter #", current, accountNumber);
		    return;
		}
		log.info("Updating Encounter # (text) to '{}'", accountNumber);
		PlayTestActionLog.update("Encounter #", "'" + current + "' -> '" + accountNumber + "' (text)");
		textInput.click(new Locator.ClickOptions().setForce(true));
		textInput.fill("");
		textInput.fill(accountNumber);
		textInput.press("Tab");
		sleep(300);
		return;
	    }

	    String current = getPageText(ENCOUNTER_CHOSEN);
	    if (valuesMatch(current, accountNumber)) {
		PlayTestActionLog.skip("Encounter #", current, accountNumber);
		return;
	    }

	    log.info("Updating Encounter # to '{}'", accountNumber);
	    PlayTestActionLog.update("Encounter #", "'" + current + "' -> '" + accountNumber + "'");
	    select2Choice.first().click(new Locator.ClickOptions().setForce(true));
	    sleep(400);

	    Locator search = visibleSelect2Search();
	    if (search == null) {
		PlayTestActionLog.skip("Encounter #", "Select2 search not visible");
		dismissSelect2();
		return;
	    }
	    search.fill(accountNumber);
	    sleep(600);

	    Locator encounterOption = page.getByText(accountNumber);
	    if (encounterOption.count() > 0) {
		encounterOption.first().click(new Locator.ClickOptions().setForce(true));
	    } else {
		log.info("not in the list this encounter number: {}", accountNumber);
		dismissSelect2();
	    }
	    sleep(200);
	} catch (Exception e) {
	    log.warn("Unable to update Encounter #: {}", e.getMessage());
	    dismissSelect2();
	}
    }

    private Locator firstVisibleEncounterTextInput() {
	String[] selectors = {
		"#patientencounternumber",
		"input[name='patientencounternumber']",
		"input[ng-model*='encounterNumber']",
		"input[ng-model*='encounter']",
		"xpath=//label[contains(text(),'Encounter')]/following-sibling::input[not(@type='hidden')]",
		"xpath=//label[contains(text(),'Encounter')]/following::input[not(@type='hidden')][1]"
	};
	for (String sel : selectors) {
	    try {
		Locator loc = page.locator(sel).first();
		if (loc.count() > 0 && loc.isVisible()) {
		    return loc;
		}
	    } catch (Exception ignored) {
	    }
	}
	return null;
    }

    /**
     * Patient/coding-header Service Location only ({@code #s2id_serviceLocation}).
     * Does not click label-based or CPT-row Service Location — those are filled later in
     * {@link Service#validateCPT}.
     */
    private void updateServiceLocation(Map<String, Object> data) {
	try {
	    Map<String, Object> batchInfo = asMap(data.get("batch_info"));
	    String batchText = batchInfo != null ? safeStr(batchInfo, "batch_text") : null;
	    String serviceLocation = batchText;
	    if (serviceLocation == null || serviceLocation.isBlank()) {
		PlayTestActionLog.noData("Service Location");
		return;
	    }

	    Locator header = page.locator(SERVICE_LOCATION_HEADER).first();
	    if (header.count() == 0) {
		PlayTestActionLog.skip("Service Location", "header #s2id_serviceLocation not on form");
		return;
	    }
	    Locator choice = page.locator(SERVICE_LOCATION_HEADER_CHOICE).first();
	    if (choice.count() == 0) {
		PlayTestActionLog.skip("Service Location", "header select2 choice not on form");
		return;
	    }

	    String current = "";
	    try {
		current = page.locator(SERVICE_LOCATION_HEADER_CHOSEN).first().innerText();
	    } catch (Exception ignored) {
	    }
	    if (valuesMatch(current, serviceLocation)) {
		PlayTestActionLog.skip("Service Location", current, serviceLocation);
		return;
	    }

	    PlayTestActionLog.update("Service Location", "'" + current + "' -> '" + serviceLocation + "'");
	    log.info("Setting header Service Location to '{}' (was '{}') — not touching CPT rows",
		    serviceLocation, current);
	    dismissSelect2();
	    choice.scrollIntoViewIfNeeded();
	    choice.click(new Locator.ClickOptions().setForce(true));
	    sleep(200);

	    Locator search = visibleSelect2Search();
	    if (search == null) {
		PlayTestActionLog.skip("Service Location", "Select2 search not visible");
		dismissSelect2();
		return;
	    }
	    search.fill(serviceLocation);
	    sleep(700);

	    Locator resultLoc = page.locator("#select2-drop").getByText(serviceLocation);
	    if (resultLoc.count() > 0) {
		resultLoc.first().click(new Locator.ClickOptions().setForce(true));
	    } else if (!selectFirstSelect2Result()) {
		dismissSelect2();
	    }
	    sleep(200);
	} catch (Exception e) {
	    log.warn("Unable to update Service Location: {}", e.getMessage());
	    dismissSelect2();
	}
    }

    /**
     * Patient/coding-header Place of Service only ({@code #s2id_placeOfService}).
     * Skips charge/CPT POS ({@code chargeplaceOfServicever}, label POS in charge panel) —
     * those are filled later in {@link Service#validateCPT}.
     */
    private void updatePlaceOfService(Map<String, Object> data) {
	String pos = null;
	try {
	    Map<String, Object> patient = asMap(data.get("patient"));
	    Map<String, Object> coding = asMap(data.get("coding"));
	    pos = firstNonBlank(safeStr(patient, "pos_code"), safeStr(patient, "pos"),
		    safeStr(coding, "place_of_service"), safeStr(coding, "pos"));
	    if (pos == null || pos.isBlank()) {
		PlayTestActionLog.noData("POS");
		return;
	    }

	    Locator container = page.locator(PLACE_OF_SERVICE).first();
	    if (container.count() == 0) {
		PlayTestActionLog.skip("POS", "header #s2id_placeOfService not on form (CPT POS deferred to validateCPT)");
		return;
	    }

	    String cls = "";
	    try {
		cls = String.valueOf(container.getAttribute("class"));
	    } catch (Exception ignored) {
	    }
	    boolean disabled = cls != null && cls.contains("select2-container-disabled");
	    if (!disabled) {
		try {
		    Locator hidden = page.locator("#placeOfService").first();
		    if (hidden.count() > 0) {
			String dis = hidden.getAttribute("disabled");
			if (dis != null) {
			    disabled = true;
			}
		    }
		} catch (Exception ignored) {
		}
	    }

	    String current = "";
	    try {
		current = page.locator(PLACE_OF_SERVICE_CHOSEN).first().innerText().trim();
	    } catch (Exception e) {
		try {
		    current = container.locator(".select2-chosen").first().innerText().trim();
		} catch (Exception ignored) {
		}
	    }
	    if (isBlankPageValue(current)) {
		current = "";
	    }

	    if (posMatches(current, pos)) {
		PlayTestActionLog.skip("POS", current, pos);
		log.info("Header POS already '{}' (matches '{}')", current, pos);
		return;
	    }

	    // JSON wins over disabled/auto POS (e.g. UI locked at 23, JSON wants 24)
	    if (disabled) {
		log.info("Header POS disabled with '{}' — force-enable and set JSON '{}'", current, pos);
		tryForceEnablePlaceOfService();
	    }

	    PlayTestActionLog.update("POS", "'" + current + "' -> '" + pos + "'");
	    log.info("Setting header POS to '{}' (was '{}') — not touching CPT/charge POS", pos, current);
	    dismissSelect2();
	    Locator choice = page.locator(PLACE_OF_SERVICE_CHOICE).first();
	    if (choice.count() == 0) {
		choice = container.locator("a.select2-choice").first();
	    }
	    if (choice.count() == 0) {
		PlayTestActionLog.skip("POS", "Select2 choice not found on header");
		return;
	    }
	    choice.scrollIntoViewIfNeeded();
	    choice.click(new Locator.ClickOptions().setForce(true));
	    sleep(200);

	    Locator search = visibleSelect2Search();
	    if (search == null) {
		log.warn("Header POS Select2 search not visible — trying jQuery/select2 fallback for '{}'", pos);
		dismissSelect2();
		tryForceEnablePlaceOfService();
		setPosViaJavascript(pos);
		return;
	    }
	    search.fill(pos);
	    sleep(600);

	    Locator results = page.locator(
		    ".select2-drop-active .select2-results li.select2-result-selectable, .select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable");
	    Locator match = null;
	    for (int i = 0; i < results.count(); i++) {
		Locator r = results.nth(i);
		String text = "";
		try {
		    text = r.innerText().trim();
		} catch (Exception ignored) {
		}
		if (posMatches(text, pos)) {
		    match = r;
		    break;
		}
	    }
	    if (match != null) {
		match.click(new Locator.ClickOptions().setForce(true));
	    } else if (results.count() > 0) {
		results.first().click(new Locator.ClickOptions().setForce(true));
	    } else if (!selectFirstSelect2Result()) {
		dismissSelect2();
		log.warn("Header POS no select2 match — trying jQuery/select2 fallback for '{}'", pos);
		setPosViaJavascript(pos);
		return;
	    }
	    sleep(400);
	    String after = "";
	    try {
		after = page.locator(PLACE_OF_SERVICE_CHOSEN).first().innerText().trim();
	    } catch (Exception ignored) {
	    }
	    if (!posMatches(after, pos)) {
		log.warn("Header POS UI still '{}' after click — JS fallback for '{}'", after, pos);
		setPosViaJavascript(pos);
	    }
	} catch (Exception e) {
	    log.warn("Unable to update POS: {}", e.getMessage());
	    dismissSelect2();
	    if (pos != null && !pos.isBlank()) {
		try {
		    setPosViaJavascript(pos);
		} catch (Exception ignored) {
		}
	    }
	}
    }

    private void setPosViaJavascript(String pos) {
	if (pos == null || pos.isBlank()) {
	    return;
	}
	tryForceEnablePlaceOfService();
	Object result = page.evaluate("(posCode) => {"
		+ "  const input = document.querySelector('#placeOfService');"
		+ "  const container = document.querySelector('#s2id_placeOfService');"
		+ "  const chosen = container && container.querySelector('.select2-chosen');"
		+ "  if (!input) return 'missing-input';"
		+ "  const $ = window.jQuery || window.$;"
		+ "  if ($ && $.fn && $.fn.select2) {"
		+ "    try {"
		+ "      $(input).select2('enable', true);"
		+ "      const data = { id: posCode, text: posCode, code: posCode };"
		+ "      $(input).select2('data', data);"
		+ "      $(input).val(posCode).trigger('change');"
		+ "      if (chosen) chosen.textContent = posCode;"
		+ "      return 'ok-select2';"
		+ "    } catch (e) { return 'select2-error:' + e.message; }"
		+ "  }"
		+ "  if (chosen) chosen.textContent = posCode;"
		+ "  input.value = posCode;"
		+ "  input.dispatchEvent(new Event('change', { bubbles: true }));"
		+ "  return 'ok-dom';"
		+ "}", pos);
	log.info("POS JS fallback result={} for '{}'", result, pos);
	PlayTestActionLog.update("POS", "JS fallback -> '" + pos + "' (" + result + ")");
	sleep(400);
    }

    /** Clears disabled state on Place of Service so an empty field can be set from JSON. */
    private void tryForceEnablePlaceOfService() {
	try {
	    page.evaluate("() => {"
		    + "  const h = document.querySelector('#placeOfService');"
		    + "  if (h) { h.removeAttribute('disabled'); h.disabled = false; }"
		    + "  const c = document.querySelector('#s2id_placeOfService');"
		    + "  if (c) {"
		    + "    c.classList.remove('select2-container-disabled');"
		    + "    c.removeAttribute('disabled');"
		    + "    const f = c.querySelector('.select2-focusser');"
		    + "    if (f) { f.removeAttribute('disabled'); f.disabled = false; }"
		    + "  }"
		    + "  const $ = window.jQuery || window.$;"
		    + "  if ($ && h && $.fn && $.fn.select2) {"
		    + "    try { $(h).select2('enable', true); } catch (e) {}"
		    + "  }"
		    + "}");
	} catch (Exception e) {
	    log.warn("Could not force-enable POS: {}", e.getMessage());
	}
    }

    /** True when page text equals pos or starts with pos code (e.g. "23 - Emergency..."). */
    private boolean posMatches(String pageVal, String pos) {
	if (pos == null || pos.isBlank()) {
	    return true;
	}
	if (pageVal == null || pageVal.isBlank() || "\u00a0".equals(pageVal.trim())) {
	    return false;
	}
	String p = pageVal.trim();
	String want = pos.trim();
	if (p.equalsIgnoreCase(want)) {
	    return true;
	}
	// "23 - Emergency Room - Hospital" / "23 Emergency..."
	if (p.regionMatches(true, 0, want, 0, want.length())) {
	    if (p.length() == want.length()) {
		return true;
	    }
	    char next = p.charAt(want.length());
	    return next == ' ' || next == '-' || next == '\t';
	}
	return false;
    }

    private void updateDateOfService(Map<String, Object> data) {
	try {
	    Map<String, Object> encounter = asMap(data.get("encounter"));
	    Map<String, Object> edProviderNotes = asMap(data.get("ed_provider_notes"));
	    String rawDos = firstNonBlank(safeStr(encounter, "date_of_service"),
		    safeStr(encounter, "encounter_date_time"), safeStr(encounter, "admit_date"),
		    safeStr(edProviderNotes, "date_of_service"),
		    safeStr(asMap(data.get("patient")), "date_of_service"));
	    if (rawDos == null || rawDos.isBlank()) {
		PlayTestActionLog.noData("Date of Service");
		return;
	    }

	    String dos = normalizeDate(rawDos);
	    if (dos == null) {
		PlayTestActionLog.noData("Date of Service", "could not parse '" + rawDos + "'");
		return;
	    }

	    if (!fieldPresent(DATE_OF_SERVICE)) {
		PlayTestActionLog.skip("Date of Service", "field not on form");
		return;
	    }

	    Locator dosLoc = page.locator(DATE_OF_SERVICE).first();
	    String current = null;
	    try {
		current = dosLoc.inputValue();
	    } catch (Exception ignored) {
	    }
	    log.info(" current " + current + ".=> " + dos);
	    if (valuesMatch(normalizeDate(current), dos)) {
		log.warn(" matched dos");
		PlayTestActionLog.skip("Date of Service", current, dos);
		return;
	    }

	    PlayTestActionLog.update("Date of Service", "'" + current + "' -> '" + dos + "'");
	    ps.setReadonlyInputValue(dosLoc, dos, "DOS");
	    sleep(200);
	} catch (Exception e) {
	    log.warn("Unable to update DOS: {}", e.getMessage());
	}
    }

    private void updateIllnessDate(Map<String, Object> data) {
	try {
	    Map<String, Object> encounter = asMap(data.get("encounter"));
	    Map<String, Object> edProviderNotes = asMap(data.get("ed_provider_notes"));
	    // Prefer accident_date for Illness Date when present (otherwise DOS)
	    String rawDos = firstNonBlank(strTop(data, "accident_date"),
		    safeStr(asMap(data.get("billing")), "accident_date"),
		    safeStr(encounter, "date_of_service"),
		    safeStr(encounter, "encounter_date_time"), safeStr(encounter, "admit_date"),
		    safeStr(edProviderNotes, "date_of_service"),
		    safeStr(asMap(data.get("patient")), "date_of_service"));
	    if (rawDos == null || rawDos.isBlank()) {
		PlayTestActionLog.noData("Illness Date");
		return;
	    }

	    String illnessDate = normalizeDate(rawDos);
	    if (illnessDate == null) {
		PlayTestActionLog.noData("Illness Date", "could not parse '" + rawDos + "'");
		return;
	    }

	    Locator illnessField = firstPresent(ILLNESS_DATE, ILLNESS_DATE_XPATH);
	    if (illnessField == null) {
		PlayTestActionLog.skip("Illness Date", "field not on form");
		return;
	    }

	    String current = null;
	    try {
		current = illnessField.inputValue();
	    } catch (Exception ignored) {
	    }
	    if (valuesMatch(normalizeDate(current), illnessDate)) {
		PlayTestActionLog.skip("Illness Date", current, illnessDate);
		return;
	    }

	    PlayTestActionLog.update("Illness Date", "'" + current + "' -> '" + illnessDate + "'");
	    ps.setReadonlyInputValue(illnessField, illnessDate, "Illness Date");
	    sleep(200);
	} catch (Exception e) {
	    log.warn("Unable to update Illness Date: {}", e.getMessage());
	}
    }

    private void updateBillingType(Map<String, Object> data) {
	String billingType = firstNonBlank(strTop(data, "billing_type"),
		safeStr(asMap(data.get("billing")), "billing_type"));
	if (billingType == null || billingType.isBlank()) {
	    PlayTestActionLog.noData("Billing Type");
	    return;
	}
	// No dedicated billing_type control on the coding form — drive flow via ED/CPT instead
	PlayTestActionLog.skip("Billing Type", "'" + billingType + "' — no UI field on form");
    }

    private void updateAccidentDate(Map<String, Object> data) {
	try {
	    String raw = firstNonBlank(strTop(data, "accident_date"),
		    safeStr(asMap(data.get("billing")), "accident_date"));
	    if (raw == null || raw.isBlank()) {
		PlayTestActionLog.noData("Accident Date");
		return;
	    }
	    String accidentDate = normalizeDate(raw);
	    if (accidentDate == null) {
		PlayTestActionLog.noData("Accident Date", "could not parse '" + raw + "'");
		return;
	    }
	    if (!fieldPresent(ACCIDENT_DATE)) {
		PlayTestActionLog.skip("Accident Date", "field not on form");
		return;
	    }
	    Locator loc = page.locator(ACCIDENT_DATE).first();
	    String current = null;
	    try {
		current = loc.inputValue();
	    } catch (Exception ignored) {
	    }
	    if (valuesMatch(normalizeDate(current), accidentDate)) {
		PlayTestActionLog.skip("Accident Date", current, accidentDate);
		return;
	    }
	    PlayTestActionLog.update("Accident Date", "'" + current + "' -> '" + accidentDate + "'");
	    ps.setReadonlyInputValue(loc, accidentDate, "Accident Date");
	    sleep(400);
	} catch (Exception e) {
	    log.warn("Unable to update Accident Date: {}", e.getMessage());
	}
    }

    private void updateAccidentType(Map<String, Object> data) {
	try {
	    String accidentType = firstNonBlank(strTop(data, "accident_type"),
		    safeStr(asMap(data.get("billing")), "accident_type"),
		    safeStr(asMap(data.get("billing")), "accident_code"));
	    if (accidentType == null || accidentType.isBlank()) {
		log.info("Accident Type: no value in JSON");
		PlayTestActionLog.noData("Accident Type");
		return;
	    }
	    accidentType = accidentType.trim();
	    // "OA - Other Accident" → use code "OA"
	    if (accidentType.contains(" - ")) {
		accidentType = accidentType.split(" - ", 2)[0].trim();
	    }
	    boolean typeOnForm = fieldPresent(ACCIDENT_TYPE_CHOICE) || fieldPresent(ACCIDENT_TYPE)
		    || fieldPresent(ACCIDENT_TYPE_SELECT);
	    if (!typeOnForm) {
		log.info("Accident Type: field not on form");
		PlayTestActionLog.skip("Accident Type", "field not on form");
		return;
	    }

	    String current = getPageText(ACCIDENT_TYPE_CHOSEN);
	    if (current != null && current.toUpperCase().contains(accidentType.toUpperCase())) {
		log.info("Accident Type already '{}', skipping", current);
		PlayTestActionLog.skip("Accident Type", current, accidentType);
		return;
	    }

	    log.info("Setting Accident Type to '{}' (was '{}')", accidentType, current);
	    PlayTestActionLog.update("Accident Type", "'" + current + "' -> '" + accidentType + "'");

	    Locator nativeSelect = page.locator(ACCIDENT_TYPE_SELECT).first();
	    if (nativeSelect.count() > 0) {
		try {
		    nativeSelect.selectOption(new SelectOption().setValue(accidentType));
		    sleep(150);
		    final String code = accidentType;
		    page.evaluate("(code) => {"
			    + "  const sel = document.querySelector('#accidenttype')"
			    + "      || document.querySelector(\"select[name='accidentType']\");"
			    + "  if (!sel) return;"
			    + "  sel.value = code;"
			    + "  sel.dispatchEvent(new Event('input', { bubbles: true }));"
			    + "  sel.dispatchEvent(new Event('change', { bubbles: true }));"
			    + "  const $ = window.jQuery || window.$;"
			    + "  if ($ && $.fn) { try { $(sel).val(code).trigger('change'); } catch (e) {} }"
			    + "  const ang = window.angular;"
			    + "  if (ang) {"
			    + "    try {"
			    + "      const scope = ang.element(sel).scope();"
			    + "      if (scope) {"
			    + "        if (scope.editor) scope.editor.accidentType = code;"
			    + "        scope.form = scope.form || {};"
			    + "        scope.form.accidentType = code;"
			    + "        if (scope.$apply) scope.$apply();"
			    + "      }"
			    + "    } catch (e2) {}"
			    + "  }"
			    + "  const chosen = document.querySelector('#s2id_accidenttype .select2-chosen');"
			    + "  if (chosen) {"
			    + "    const opt = sel.options[sel.selectedIndex];"
			    + "    chosen.textContent = (opt && opt.text) ? opt.text : code;"
			    + "  }"
			    + "}", code);
		    sleep(200);
		    String afterNative = "";
		    try {
			afterNative = nativeSelect.inputValue();
		    } catch (Exception ignored) {
		    }
		    String afterChosen = getPageText(ACCIDENT_TYPE_CHOSEN);
		    if (code.equalsIgnoreCase(afterNative != null ? afterNative.trim() : "")
			    || (afterChosen != null && afterChosen.toUpperCase().contains(code.toUpperCase()))) {
			log.info("Accident Type set via native select to '{}'", afterNative);
			return;
		    }
		    log.warn("Accident Type native select did not stick (value='{}', chosen='{}') — trying Select2",
			    afterNative, afterChosen);
		} catch (Exception e) {
		    log.warn("Accident Type native select failed: {} — trying Select2", e.getMessage());
		}
	    }

	    dismissSelect2();
	    Locator choice = page.locator(ACCIDENT_TYPE_CHOICE).first();
	    if (choice.count() == 0) {
		log.warn("Accident Type Select2 choice not on form");
		PlayTestActionLog.skip("Accident Type", "Select2 choice not on form");
		return;
	    }
	    choice.click(new Locator.ClickOptions().setForce(true));
	    sleep(400);
	    Locator search = visibleSelect2Search();
	    if (search != null) {
		search.fill(accidentType);
		sleep(350);
	    } else {
		log.warn("Accident Type Select2 search not visible after click");
	    }
	    Locator option = page.locator(
		    ".select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable")
		    .filter(new Locator.FilterOptions().setHasText(accidentType));
	    if (option.count() > 0) {
		option.first().click(new Locator.ClickOptions().setForce(true));
	    } else if (!selectFirstSelect2Result()) {
		dismissSelect2();
		log.warn("Accident Type: no matching Select2 option for '{}'", accidentType);
		PlayTestActionLog.skip("Accident Type", "no matching option for '" + accidentType + "'");
		return;
	    }
	    sleep(400);
	} catch (Exception e) {
	    log.warn("Unable to update Accident Type: {}", e.getMessage());
	    dismissSelect2();
	}
    }

    /**
     * critical_care has no dedicated form control; when non-null, ensure CPT 99291 (or
     * an explicit code from the payload) is available for validateCPT. Actual charge
     * row fill happens in Service.validateCPT.
     */
    private void updateCriticalCare(Map<String, Object> data) {
	Object cc = data.get("critical_care");
	if (cc == null || "null".equalsIgnoreCase(String.valueOf(cc).trim())) {
	    PlayTestActionLog.noData("Critical Care");
	    return;
	}
	String code = resolveCriticalCareCpt(cc);
	if (code == null) {
	    PlayTestActionLog.skip("Critical Care", "payload present but no CPT code inferred — no UI field");
	    return;
	}
	// Stash for callers / logging; CPT row is filled by Service.validateCPT
	data.put("_critical_care_cpt", code);
	PlayTestActionLog.skip("Critical Care",
		"no dedicated UI field; CPT '" + code + "' should be applied via validateCPT");
    }

    private String resolveCriticalCareCpt(Object cc) {
	if (cc instanceof Map<?, ?> m) {
	    @SuppressWarnings("unchecked")
	    Map<String, Object> map = (Map<String, Object>) m;
	    String code = firstNonBlank(safeStr(map, "code"), safeStr(map, "cpt"), safeStr(map, "cpt_code"),
		    safeStr(map, "em_cpt_code"));
	    if (code != null)
		return code.trim();
	    // Any non-empty critical care object without a code → default first-hour CC
	    return "99291";
	}
	if (cc instanceof Boolean b) {
	    return b ? "99291" : null;
	}
	String s = String.valueOf(cc).trim();
	if (s.isEmpty() || "false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s))
	    return null;
	if (s.matches("\\d{5}"))
	    return s;
	if ("true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s))
	    return "99291";
	return "99291";
    }

    /** Top-level string from validation map (accident_date, billing_type, …). */
    private String strTop(Map<String, Object> data, String key) {
	if (data == null)
	    return null;
	Object v = data.get(key);
	if (v == null)
	    return null;
	String s = String.valueOf(v).trim();
	return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    /**
     * Strict payload rule only:
     * - admitted "yes" / true / y → click Yes
     * - admitted "no" (or anything else / missing) → do not touch Admitted
     */
    private void updateAdmitted(Map<String, Object> data) {
	try {
	    Map<String, Object> encounter = asMap(data.get("encounter"));
	    Map<String, Object> patient = asMap(data.get("patient"));

	    String admittedFlag = firstNonBlank(safeStr(patient, "admitted"), safeStr(encounter, "admitted"),
		    strTop(data, "admitted"));
	    boolean wantAdmitted = admittedFlag != null
		    && ("yes".equalsIgnoreCase(admittedFlag) || "true".equalsIgnoreCase(admittedFlag)
			    || "y".equalsIgnoreCase(admittedFlag));

	    if (!wantAdmitted) {
		PlayTestActionLog.skip("Admitted",
			admittedFlag == null || admittedFlag.isBlank() ? "not requested (no admitted flag)"
				: "admitted='" + admittedFlag + "' — leave untouched");
		return;
	    }

	    Locator yesBtn = page.locator(ADMITTED_YES);
	    if (yesBtn.count() == 0) {
		PlayTestActionLog.skip("Admitted", "Yes button not on form");
		return;
	    }
	    PlayTestActionLog.update("Admitted", "clicking Yes (admitted=yes)");
	    yesBtn.first().click(new Locator.ClickOptions().setForce(true));
	    sleep(200);
	} catch (Exception e) {
	    log.warn("Unable to update Admitted: {}", e.getMessage());
	}
    }

    private boolean isEDEncounter(Map<String, Object> encounter, Map<String, Object> coding) {
	if (page.locator("xpath=//div[@ng-if='reportBatch.$isED']").count() > 0)
	    return true;
	String loc = safeStr(coding, "service_location");
	String dept = safeStr(coding, "department");
	String svc = safeStr(encounter, "service");
	String combined = ((loc != null ? loc : "") + " " + (dept != null ? dept : "") + " " + (svc != null ? svc : ""))
		.toUpperCase();
	return combined.contains("ED") || combined.contains("EMERGENCY") || combined.contains("ER ");
    }

    private boolean isPatientDischarged(Map<String, Object> encounter, Map<String, Object> coding) {
	String status = safeStr(encounter, "status");
	if (status != null) {
	    String u = status.toUpperCase();
	    if (u.contains("DISCHARG") || u.contains("DC'D") || u.contains("DCD") || u.contains("EXPIRED")
		    || u.contains("DECEASED") || u.contains("TRANSFER") || u.contains("AMA"))
		return true;
	}
	@SuppressWarnings("unchecked")
	List<Object> disposition = coding != null ? (List<Object>) coding.get("disposition") : null;
	if (disposition != null) {
	    for (Object d : disposition) {
		String ds = String.valueOf(d).toUpperCase();
		if (ds.contains("DISCHARG") || ds.contains("HOME") || ds.contains("AMA") || ds.contains("LEFT")
			|| ds.contains("EXPIRED") || ds.contains("DECEASED") || ds.contains("TRANSFER"))
		    return true;
	    }
	}
	return false;
    }

    /**
     * Header Department Select2 ({@code input[name=department]}) when JSON provides a value.
     */
    private void updateDepartment(Map<String, Object> data) {
	try {
	    Map<String, Object> coding = asMap(data.get("coding"));
	    Map<String, Object> patient = asMap(data.get("patient"));
	    Map<String, Object> encounter = asMap(data.get("encounter"));
	    String department = firstNonBlank(safeStr(coding, "department"), safeStr(patient, "department"),
		    safeStr(encounter, "service"), safeStr(encounter, "department"), strTop(data, "department"));
	    if (department == null || department.isBlank()) {
		PlayTestActionLog.noData("Department");
		return;
	    }

	    Locator choice = page.locator(DEPARTMENT_CHOICE).first();
	    if (choice.count() == 0) {
		Locator input = page.locator(DEPARTMENT_INPUT).first();
		if (input.count() > 0) {
		    choice = input.locator(
			    "xpath=preceding-sibling::div[contains(@class,'select2-container')][1]//a[contains(@class,'select2-choice')]")
			    .first();
		}
	    }
	    if (choice.count() == 0 || !choice.isVisible()) {
		PlayTestActionLog.skip("Department", "field not on form");
		return;
	    }

	    String current = getPageText(DEPARTMENT_CHOSEN);
	    if (current == null || isBlankPageValue(current)) {
		try {
		    current = choice.locator(
			    "xpath=ancestor::div[contains(@class,'select2-container')][1]//span[contains(@class,'select2-chosen')]")
			    .first().innerText();
		} catch (Exception ignored) {
		}
	    }
	    if (valuesMatch(current, department)
		    || (current != null && current.toLowerCase().contains(department.toLowerCase()))) {
		PlayTestActionLog.skip("Department", current, department);
		return;
	    }

	    PlayTestActionLog.update("Department", "'" + current + "' -> '" + department + "'");
	    log.info("Setting Department to '{}' (was '{}')", department, current);
	    dismissSelect2();
	    choice.scrollIntoViewIfNeeded();
	    choice.click(new Locator.ClickOptions().setForce(true));
	    sleep(200);
	    Locator search = visibleSelect2Search();
	    if (search == null) {
		PlayTestActionLog.skip("Department", "Select2 search not visible");
		dismissSelect2();
		return;
	    }
	    search.fill(department);
	    sleep(600);
	    Locator match = page.locator(SELECT2_RESULTS)
		    .filter(new Locator.FilterOptions().setHasText(department));
	    if (match.count() > 0) {
		match.first().click(new Locator.ClickOptions().setForce(true));
	    } else if (!selectFirstSelect2Result()) {
		dismissSelect2();
		PlayTestActionLog.skip("Department", "no Select2 match for '" + department + "'");
		return;
	    }
	    sleep(200);
	} catch (Exception e) {
	    log.warn("Unable to update Department: {}", e.getMessage());
	    dismissSelect2();
	}
    }

    /**
     * Header Disposition multi-Select2 when JSON provides labels
     * ({@code disposition_labels}, {@code coding.disposition}, {@code ed_disposition}).
     */
    private void updateDisposition(Map<String, Object> data) {
	try {
	    List<String> labels = new java.util.ArrayList<>();
	    Object mapped = data.get("disposition_labels");
	    if (mapped instanceof List<?> list) {
		for (Object o : list) {
		    if (o != null && !String.valueOf(o).isBlank()) {
			labels.add(String.valueOf(o).trim());
		    }
		}
	    }
	    if (labels.isEmpty()) {
		Map<String, Object> coding = asMap(data.get("coding"));
		Object disp = coding != null ? coding.get("disposition") : null;
		if (disp instanceof List<?> list) {
		    for (Object o : list) {
			if (o != null && !String.valueOf(o).isBlank()) {
			    labels.add(String.valueOf(o).trim());
			}
		    }
		} else if (disp instanceof String s && !s.isBlank()) {
		    labels.add(s.trim());
		}
	    }
	    if (labels.isEmpty()) {
		String fromEd = getDispositionStatus(asMap(data.get("ed_disposition")) != null
			? asMap(data.get("ed_disposition"))
			: data);
		if (fromEd == null) {
		    fromEd = getDispositionStatus(data);
		}
		if (fromEd != null && !fromEd.isBlank()) {
		    labels.add(ResumePayloadMapper.mapDispoEsc(fromEd));
		}
	    }
	    labels.removeIf(s -> s == null || s.isBlank());
	    if (labels.isEmpty()) {
		PlayTestActionLog.noData("Disposition");
		return;
	    }

	    Locator container = page.locator(DISPOSITION).first();
	    if (container.count() == 0 || !container.isVisible()) {
		PlayTestActionLog.skip("Disposition", "field not on form");
		return;
	    }

	    String already = "";
	    try {
		already = container.innerText();
	    } catch (Exception ignored) {
	    }
	    String alreadyLower = already != null ? already.toLowerCase() : "";

	    for (String label : labels) {
		if (label != null && alreadyLower.contains(label.toLowerCase())) {
		    PlayTestActionLog.skip("Disposition", "already has '" + label + "'");
		    continue;
		}
		PlayTestActionLog.update("Disposition", "add '" + label + "'");
		log.info("Adding Disposition '{}'", label);
		dismissSelect2();
		Locator choices = page.locator(DISPOSITION_CHOICES).first();
		if (choices.count() == 0) {
		    choices = container;
		}
		choices.click(new Locator.ClickOptions().setForce(true));
		sleep(200);
		Locator search = visibleSelect2Search();
		if (search == null) {
		    search = page.locator(DISPOSITION + " input.select2-input").first();
		}
		if (search == null || search.count() == 0) {
		    PlayTestActionLog.skip("Disposition", "Select2 search not visible for '" + label + "'");
		    dismissSelect2();
		    continue;
		}
		search.fill(label);
		sleep(700);
		Locator results = page.locator(SELECT2_RESULTS);
		Locator match = null;
		String want = label.toLowerCase();
		for (int i = 0; i < results.count(); i++) {
		    String t = results.nth(i).innerText().trim().toLowerCase();
		    if (t.contains(want) || want.contains(t)) {
			match = results.nth(i);
			break;
		    }
		}
		if (match != null) {
		    match.click(new Locator.ClickOptions().setForce(true));
		    sleep(250);
		} else if (!selectFirstSelect2Result()) {
		    PlayTestActionLog.skip("Disposition", "no Select2 match for '" + label + "'");
		    dismissSelect2();
		}
		dismissSelect2();
	    }
	} catch (Exception e) {
	    log.warn("Unable to update Disposition: {}", e.getMessage());
	    dismissSelect2();
	}
    }

    private void updateProviders(Map<String, Object> data) {
	try {
	    String supervisingName = directProviderName(data, "supervising_provider", "supervising");
	    String renderingName = directProviderName(data, "rendering_provider", "rendering");

	    if (supervisingName != null || renderingName != null) {
		if (supervisingName != null) {
		    setSelect2IfDifferent(SUPERVISING_CHOSEN, SUPERVISING_CHOICE, supervisingName,
			    "Supervising Provider");
		} else {
		    PlayTestActionLog.noData("Supervising Provider");
		}
		if (renderingName != null) {
		    setSelect2IfDifferent(RENDERING_CHOSEN, RENDERING_CHOICE, renderingName, "Rendering Provider");
		} else {
		    PlayTestActionLog.noData("Rendering Provider");
		}
		return;
	    }

	    Map<String, Object> providers = asMap(data.get("providers"));
	    if (providers == null) {
		PlayTestActionLog.noData("Providers");
		return;
	    }

	    String mdName = findProviderName(providers, "MD");
	    String paName = findProviderName(providers, "PA");

	    if (mdName != null && paName != null) {
		setSelect2IfDifferent(SUPERVISING_CHOSEN, SUPERVISING_CHOICE, mdName, "Supervising Provider");
		setSelect2IfDifferent(RENDERING_CHOSEN, RENDERING_CHOICE, paName, "Rendering Provider");
	    } else if (mdName != null) {
		setSelect2IfDifferent(RENDERING_CHOSEN, RENDERING_CHOICE, mdName, "Rendering Provider");
	    } else if (paName != null) {
		setSelect2IfDifferent(RENDERING_CHOSEN, RENDERING_CHOICE, paName, "Rendering Provider");
	    } else {
		PlayTestActionLog.noData("Providers", "no MD/PA names inferred from providers map");
	    }
	} catch (Exception e) {
	    log.warn("Unable to update Providers: {}", e.getMessage());
	}
    }

    /**
     * Resume payload: use patient.rendering_provider / supervising_provider as-is
     * when present.
     */
    private String directProviderName(Map<String, Object> data, String patientKey, String providersSlotKey) {
	Map<String, Object> patient = asMap(data.get("patient"));
	if (patient != null) {
	    String fromPatient = safeStr(patient, patientKey);
	    if (fromPatient != null && !fromPatient.isBlank()) {
		return fromPatient.trim();
	    }
	}
	Map<String, Object> providers = asMap(data.get("providers"));
	if (providers != null) {
	    Map<String, Object> slot = asMap(providers.get(providersSlotKey));
	    String fromSlot = safeStr(slot, "name");
	    if (fromSlot != null && !fromSlot.isBlank()) {
		return fromSlot.trim();
	    }
	}
	return null;
    }

    private void setSelect2IfDifferent(String chosenXpath, String choiceXpath, String expected, String desc) {
	if (expected == null || expected.isBlank()) {
	    PlayTestActionLog.noData(desc);
	    return;
	}

	Locator choice = page.locator(choiceXpath);
	if (choice.count() == 0 && RENDERING_CHOICE.equals(choiceXpath)) {
	    choice = page.locator(RENDERING_CHOICE_XPATH);
	    chosenXpath = RENDERING_CHOSEN_XPATH;
	}
	if (choice.count() == 0) {
	    PlayTestActionLog.skip(desc, "field not on form");
	    return;
	}

	String current = getPageText(chosenXpath);
	if (isBlankPageValue(current)) {
	    PlayTestActionLog.update(desc, "empty -> '" + expected + "'");
	} else if (providerNamesMatch(current, expected)) {
	    PlayTestActionLog.skip(desc, current, expected);
	    return;
	} else {
	    PlayTestActionLog.update(desc, "'" + current + "' -> '" + expected + "'");
	}

	try {
	    dismissSelect2();
	    choice.first().scrollIntoViewIfNeeded();
	    choice.first().click(new Locator.ClickOptions().setForce(true));
	    sleep(400);

	    Locator searchInput = visibleSelect2Search();
	    if (searchInput == null) {
		PlayTestActionLog.skip(desc, "Select2 search not visible");
		dismissSelect2();
		return;
	    }
	    searchInput.fill(expected);
	    sleep(600);
	    if (!selectFirstSelect2Result()) {
		log.warn("{}: no Select2 result for '{}'", desc, expected);
		dismissSelect2();
	    }
	} catch (Exception e) {
	    log.warn("Unable to update {}: {}", desc, e.getMessage());
	    dismissSelect2();
	}
    }

    private String findProviderName(Map<String, Object> providers, String titleKey) {
	Map<String, Object> supervising = asMap(providers.get("supervising"));
	Map<String, Object> rendering = asMap(providers.get("rendering"));
	Map<String, Object> pa = asMap(providers.get("physician_assistant"));

	@SuppressWarnings("unchecked")
	List<Map<String, Object>> allProviders = providers.get("all_providers") instanceof List
		? (List<Map<String, Object>>) providers.get("all_providers")
		: null;

	@SuppressWarnings("unchecked")
	List<Map<String, Object>> others = providers.get("other") instanceof List
		? (List<Map<String, Object>>) providers.get("other")
		: null;

	if ("MD".equals(titleKey)) {
	    if (allProviders != null) {
		for (Map<String, Object> p : allProviders) {
		    if ("md".equalsIgnoreCase(safeStr(p, "role")) && safeStr(p, "name") != null)
			return safeStr(p, "name");
		}
	    }
	    if (supervising != null && hasMdTitle(supervising))
		return safeStr(supervising, "name");
	    if (rendering != null && hasMdTitle(rendering))
		return safeStr(rendering, "name");
	    if (others != null) {
		for (Map<String, Object> o : others) {
		    if (hasMdTitle(o))
			return safeStr(o, "name");
		}
	    }
	    if (supervising != null && safeStr(supervising, "name") != null && pa == null && rendering == null)
		return safeStr(supervising, "name");
	    if (rendering != null && safeStr(rendering, "name") != null && !hasPaTitle(rendering))
		return safeStr(rendering, "name");
	}
	if ("PA".equals(titleKey)) {
	    if (allProviders != null) {
		for (Map<String, Object> p : allProviders) {
		    if ("pa".equalsIgnoreCase(safeStr(p, "role")) && safeStr(p, "name") != null)
			return safeStr(p, "name");
		}
	    }
	    if (pa != null && safeStr(pa, "name") != null)
		return safeStr(pa, "name");
	    if (rendering != null && hasPaTitle(rendering))
		return safeStr(rendering, "name");
	    if (others != null) {
		for (Map<String, Object> o : others) {
		    if (hasPaTitle(o))
			return safeStr(o, "name");
		}
	    }
	}
	return null;
    }

    private boolean hasMdTitle(Map<String, Object> provider) {
	String combined = ((safeStr(provider, "name") != null ? safeStr(provider, "name") : "") + " "
		+ (safeStr(provider, "role") != null ? safeStr(provider, "role") : "")).toUpperCase();
	return combined.contains(" MD") || combined.contains("M.D.") || combined.contains("DO ")
		|| combined.contains("PHYSICIAN") || combined.contains("DOCTOR") || combined.contains("ATTENDING");
    }

    private boolean hasPaTitle(Map<String, Object> provider) {
	String combined = ((safeStr(provider, "name") != null ? safeStr(provider, "name") : "") + " "
		+ (safeStr(provider, "role") != null ? safeStr(provider, "role") : "")).toUpperCase();
	return combined.contains(" PA") || combined.contains("P.A.") || combined.contains("NP ")
		|| combined.contains("NURSE PRACTITIONER") || combined.contains("PHYSICIAN ASSISTANT");
    }

    private boolean providerNamesMatch(String current, String expected) {
	if (expected == null || expected.isBlank())
	    return true;
	if (isBlankPageValue(current))
	    return false;
	if (current == null)
	    return false;

	String c = current.trim().toUpperCase();
	String e = expected.trim().toUpperCase();
	if (c.equals(e))
	    return true;
	String cLast = c.contains(",") ? c.split(",")[0].trim() : c;
	String eLast = e.contains(",") ? e.split(",")[0].trim() : e;
	if (cLast.isEmpty() || eLast.isEmpty())
	    return false;
	return c.contains(eLast) || e.contains(cLast);
    }

    private boolean isBlankPageValue(String value) {
	if (value == null)
	    return true;
	String t = value.trim();
	return t.isEmpty() || "\u00a0".equals(t) || "null".equalsIgnoreCase(t);
    }

    private void updateDiagnosisCodes(Map<String, Object> data) {
	try {
	    if (!fieldPresent("div.form-diagnosis-codes") && page.locator(DIAGNOSIS_SELECT2_LAST).count() == 0) {
		PlayTestActionLog.skip("Diagnosis Codes", "field not on form");
		return;
	    }

	    @SuppressWarnings("unchecked")
	    List<Object> icdCodes = data.get("billing") instanceof Map
		    ? (List<Object>) ((Map<String, Object>) data.get("billing")).get("icd_codes")
		    : null;
	    @SuppressWarnings("unchecked")
	    List<Object> diagnoses = data.get("diagnoses") instanceof List ? (List<Object>) data.get("diagnoses")
		    : null;

	    if ((icdCodes == null || icdCodes.isEmpty()) && (diagnoses == null || diagnoses.isEmpty())) {
		PlayTestActionLog.noData("Diagnosis Codes");
		return;
	    }

	    if (icdCodes != null && !icdCodes.isEmpty()) {
		for (Object code : icdCodes) {
		    String icd = String.valueOf(code).trim();
		    if (!icd.isEmpty() && !"null".equals(icd))
			addDiagnosisCode(icd);
		}
	    } else if (diagnoses != null && !diagnoses.isEmpty()) {
		for (Object diag : diagnoses) {
		    String code = null;
		    if (diag instanceof Map) {
			@SuppressWarnings("unchecked")
			Map<String, Object> m = (Map<String, Object>) diag;
			code = firstNonBlank(safeStr(m, "code"), safeStr(m, "icd_code"));
		    } else {
			code = String.valueOf(diag).trim();
		    }
		    if (code != null && !code.isEmpty() && !"null".equals(code))
			addDiagnosisCode(code);
		}
	    }
	} catch (Exception e) {
	    log.warn("Unable to update Diagnosis Codes: {}", e.getMessage());
	}
    }

    private void addDiagnosisCode(String icdCode) {
	try {
	    dismissSelect2();
	    Locator choice = page.locator(DIAGNOSIS_SELECT2_LAST);
	    if (choice.count() == 0) {
		PlayTestActionLog.skip("Diagnosis Code " + icdCode, "select2 not on page");
		return;
	    }
	    PlayTestActionLog.add("Diagnosis Code", icdCode);
	    choice.first().click(new Locator.ClickOptions().setForce(true));
	    sleep(200);
	    Locator searchInput = visibleSelect2Search();
	    if (searchInput == null) {
		PlayTestActionLog.skip("Diagnosis Code " + icdCode, "Select2 search not visible");
		dismissSelect2();
		return;
	    }
	    searchInput.fill(icdCode);
	    sleep(700);
	    selectFirstSelect2Result();
	    sleep(200);
	} catch (Exception e) {
	    log.warn("Unable to add Diagnosis Code {}: {}", icdCode, e.getMessage());
	    dismissSelect2();
	}
    }

    /** Returns true if typing into Select2 succeeded (dropdown opened and text entered). */
    private boolean typeIntoSelect2(String choiceSelector, String text, String desc) {
	log.info("Updating {} to '{}'", desc, text);
	try {
	    dismissSelect2();
	    Locator choice = page.locator(choiceSelector);
	    if (choice.count() == 0) {
		log.info("{}: Select2 choice not on form — skipping", desc);
		return false;
	    }
	    choice.first().scrollIntoViewIfNeeded();
	    choice.first().click(new Locator.ClickOptions().setForce(true));
	    sleep(200);

	    Locator searchInput = visibleSelect2Search();
	    if (searchInput == null) {
		log.info("{}: Select2 search not visible — skipping", desc);
		dismissSelect2();
		return false;
	    }
	    searchInput.click(new Locator.ClickOptions().setForce(true));
	    searchInput.fill("");
	    sleep(200);
	    searchInput.pressSequentially(text, new Locator.PressSequentiallyOptions().setDelay(50));
	    return true;
	} catch (Exception e) {
	    log.warn("Unable to type into Select2 for {}: {}", desc, e.getMessage());
	    dismissSelect2();
	    return false;
	}
    }

    private boolean fieldPresent(String selector) {
	try {
	    Locator loc = page.locator(selector).first();
	    return loc.count() > 0;
	} catch (Exception e) {
	    return false;
	}
    }

    /** First matching selector that exists in the DOM, or null. */
    private Locator firstPresent(String... selectors) {
	for (String sel : selectors) {
	    try {
		Locator loc = page.locator(sel).first();
		if (loc.count() > 0) {
		    return loc;
		}
	    } catch (Exception ignored) {
	    }
	}
	return null;
    }

    private boolean selectFirstSelect2Result() {
	for (int i = 0; i < 5; i++) {
	    sleep(600);
	    if (page.locator(SELECT2_NO_RESULTS).count() > 0)
		return false;
	    Locator results = page.locator(SELECT2_RESULTS);
	    if (results.count() > 0) {
		try {
		    results.first().click(new Locator.ClickOptions().setForce(true));
		    sleep(200);
		    return true;
		} catch (Exception e) {
		    log.warn("Could not click Select2 result: {}", e.getMessage());
		    return false;
		}
	    }
	}
	return false;
    }

    /** Prefer active drop search; fall back to focused .select2-input. */
    private Locator visibleSelect2Search() {
	String[] selectors = {
		SELECT2_SEARCH_INPUT,
		".select2-drop-active input.select2-input",
		".select2-drop:not(.select2-display-none) input.select2-input",
		"input.select2-input.select2-focused",
		"input.select2-input:focus"
	};
	for (String sel : selectors) {
	    try {
		Locator loc = page.locator(sel).first();
		if (loc.count() > 0 && loc.isVisible()) {
		    return loc;
		}
	    } catch (Exception ignored) {
	    }
	}
	return null;
    }

    /** Closes open Select2 dropdown / mask so it cannot intercept later clicks. */
    private void dismissSelect2() {
	try {
	    if (page.locator("#select2-drop-mask").count() > 0
		    || page.locator(".select2-drop-active, .select2-drop:not(.select2-display-none)").count() > 0) {
		page.keyboard().press("Escape");
		sleep(200);
		page.keyboard().press("Escape");
		sleep(200);
	    }
	    Locator mask = page.locator("#select2-drop-mask");
	    if (mask.count() > 0) {
		try {
		    mask.first().click(new Locator.ClickOptions().setForce(true).setTimeout(1000));
		} catch (Exception ignored) {
		}
		sleep(150);
	    }
	} catch (Exception ignored) {
	}
    }

    /** Reads text from page (e.g. select2-chosen). Returns null if not found. */
    private String getPageText(String selector) {
	try {
	    Locator loc = ps.locator(selector);
	    if (loc.count() > 0)
		return ps.getText(loc.first(), "page value");
	} catch (Exception e) {
	    log.debug("Could not read page text: {}", e.getMessage());
	}
	return null;
    }

    private boolean valuesMatch(String pageVal, String objectVal) {
	if (objectVal == null || objectVal.isBlank())
	    return true;
	if (pageVal == null || pageVal.isBlank() || "\u00a0".equals(pageVal.trim()))
	    return false;
	return pageVal.trim().equalsIgnoreCase(objectVal.trim());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
	return obj instanceof Map ? (Map<String, Object>) obj : null;
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

    @SuppressWarnings("unchecked")
    private String getDispositionStatus(Map<String, Object> data) {
	Object disp = data.get("disposition");
	if (disp instanceof Map)
	    return safeStr((Map<String, Object>) disp, "status");
	return safeStr(data, "disposition");
    }

    private String firstNonBlank(String... candidates) {
	for (String s : candidates) {
	    if (s != null && !s.isBlank())
		return s;
	}
	return null;
    }

    private String normalizeDate(String raw) {
	if (raw == null || raw.isBlank())
	    return null;
	String trimmed = raw.trim();
	for (DateTimeFormatter fmt : DATE_PARSERS) {
	    try {
		if (trimmed.contains(":") || trimmed.toLowerCase().contains("am")
			|| trimmed.toLowerCase().contains("pm")) {
		    return LocalDateTime.parse(trimmed, fmt).toLocalDate().format(FORM_DATE_FMT);
		}
		return LocalDate.parse(trimmed, fmt).format(FORM_DATE_FMT);
	    } catch (DateTimeParseException ignored) {
	    }
	}
	for (DateTimeFormatter fmt : DATE_PARSERS) {
	    try {
		return LocalDate.parse(trimmed, fmt).format(FORM_DATE_FMT);
	    } catch (DateTimeParseException ignored) {
	    }
	}
	log.warn("Could not parse date '{}'", raw);
	return null;
    }

    private void sleep(int millis) {
	try {
	    Thread.sleep(millis);
	} catch (InterruptedException e) {
	    Thread.currentThread().interrupt();
	}
    }
}
