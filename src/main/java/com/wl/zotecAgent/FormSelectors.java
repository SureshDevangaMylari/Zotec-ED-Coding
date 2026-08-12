package com.wl.zotecAgent;

/**
 * XPath-based selectors for the Zotec coding form (matches html.htm structure).
 * Uses label text to avoid auto-generated id/class names. Only update when page value differs from object.
 */
public final class FormSelectors {

    private FormSelectors() {}

    private static final String XP = "xpath=";

    // Patient - #s2id_patient is stable (from html.htm)
    public static final String PATIENT = "#s2id_patient";
    public static final String PATIENT_CHOSEN = "#s2id_patient .select2-chosen";
    public static final String PATIENT_CHOICE = "#s2id_patient .select2-choice";

    // Profile — native <select id="profileId"> + Select2 wrapper
    public static final String PROFILE = "#s2id_profileId";
    public static final String PROFILE_CHOSEN = PROFILE + " .select2-chosen";
    public static final String PROFILE_CHOICE = PROFILE + " .select2-choice";
    public static final String PROFILE_SELECT = "#profileId, select[name='profileId']";

    // MRN (read-only static text next to Patient)
    public static final String MRN_STATIC = XP
	    + "//label[contains(normalize-space(),'MRN')]/following::p[contains(@class,'form-control-static')][1]";

    // Department (header info form — input name=department)
    public static final String DEPARTMENT_INPUT = "input[name='department']";
    public static final String DEPARTMENT_CHOICE = XP
	    + "//label[normalize-space()='Department']/following::div[contains(@class,'select2-container')][1]//a[contains(@class,'select2-choice')]";
    public static final String DEPARTMENT_CHOSEN = XP
	    + "//label[normalize-space()='Department']/following::div[contains(@class,'select2-container')][1]//span[contains(@class,'select2-chosen')]";

    // Disposition (multi Select2)
    public static final String DISPOSITION = "#s2id_dispositionSelect";
    public static final String DISPOSITION_CHOICES = DISPOSITION + " ul.select2-choices";
    public static final String DISPOSITION_SEARCH = DISPOSITION
	    + " input.select2-input, #select2-drop-multi input.select2-input, "
	    + ".select2-drop-active input.select2-input";

    // Encounter # (stable id from html.htm)
    public static final String ENCOUNTER_CONTAINER = "#s2id_patientencounternumber";
    public static final String ENCOUNTER_CHOSEN = ENCOUNTER_CONTAINER + " .select2-chosen";
    public static final String ENCOUNTER_CHOICE = ENCOUNTER_CONTAINER + " .select2-choice";
    // Search input scoped to open dropdown (avoids matching hidden inputs from other select2s)
    public static final String ENCOUNTER_SEARCH_INPUT = XP + "//div[contains(@class,'select2-drop') and not(contains(@class,'select2-display-none'))]//input[contains(@class,'select2-input')]";

    // Service Location — header #s2id_serviceLocation when present; else label-based (e.g. charge override)
    public static final String SERVICE_LOCATION_HEADER = "#s2id_serviceLocation";
    public static final String SERVICE_LOCATION_HEADER_CHOICE = SERVICE_LOCATION_HEADER + " .select2-choice";
    public static final String SERVICE_LOCATION_CHOSEN = XP + "//label[contains(text(),'Service Location')]/following-sibling::div[contains(@class,'select2')]//span[contains(@class,'select2-chosen')]";
    public static final String SERVICE_LOCATION_CHOICE = XP + "//label[contains(text(),'Service Location')]/following-sibling::div[contains(@class,'select2')]//a[contains(@class,'select2-choice')]";

    // POS — often auto-set from Service Location and disabled; locate by id or label
    public static final String PLACE_OF_SERVICE = "#s2id_placeOfService";
    public static final String PLACE_OF_SERVICE_CHOSEN = PLACE_OF_SERVICE + " .select2-chosen";
    public static final String PLACE_OF_SERVICE_CHOICE = PLACE_OF_SERVICE + " .select2-choice";
    public static final String PLACE_OF_SERVICE_ANY =
	    "#s2id_placeOfService, div.select2-container[id*='placeOfService']";
    /** Label-based POS (header or charge override) when #s2id_placeOfService is absent. */
    public static final String PLACE_OF_SERVICE_BY_LABEL = XP
	    + "//label[normalize-space()='POS' or contains(normalize-space(),'POS')]"
	    + "/following::div[contains(@class,'select2-container')][1]";
    public static final String PLACE_OF_SERVICE_CHARGE_INPUT =
	    "input[name='chargeplaceOfServicever'], input#placeOfService, input[name='placeOfService']";

    // DOS - readonly datepicker input (id from html.htm)
    public static final String DATE_OF_SERVICE = "#dateOfService";

    // Illness Date — prefer stable id; xpath as fallback
    public static final String ILLNESS_DATE = "#illnessDate";
    public static final String ILLNESS_DATE_XPATH = XP + "//label[contains(text(),'Illness Date')]/following-sibling::input";

    // Accident Date / Type — only visible when an accident ICD is selected
    public static final String ACCIDENT_DATE = "#accidentDate";
    public static final String ACCIDENT_TYPE = "#s2id_accidenttype";
    public static final String ACCIDENT_TYPE_CHOICE = ACCIDENT_TYPE + " .select2-choice";
    public static final String ACCIDENT_TYPE_CHOSEN = ACCIDENT_TYPE + " .select2-chosen";

    // Admitted — Yes button when not yet admitted; Admit Date when already admitted
    public static final String ADMITTED_YES = XP + "//label[contains(text(),'Admitted')]/following-sibling::div//button[text()='Yes']";
    public static final String ADMITTED_DATE = "#admittedDate";

    // Supervising Provider
    public static final String SUPERVISING_CHOSEN = XP + "//label[contains(text(),'Supervising Provider')]/following-sibling::*//span[contains(@class,'select2-chosen')]";
    public static final String SUPERVISING_CHOICE = XP + "//label[contains(text(),'Supervising Provider')]/following-sibling::*//a[contains(@class,'select2-choice')]";

    // Rendering Provider — prefer stable id
    public static final String RENDERING = "#s2id_renderingprovider";
    public static final String RENDERING_CHOSEN = RENDERING + " .select2-chosen";
    public static final String RENDERING_CHOICE = RENDERING + " .select2-choice";
    public static final String RENDERING_CHOSEN_XPATH = XP + "//label[contains(text(),'Rendering Provider')]/following-sibling::*//span[contains(@class,'select2-chosen')]";
    public static final String RENDERING_CHOICE_XPATH = XP + "//label[contains(text(),'Rendering Provider')]/following-sibling::*//a[contains(@class,'select2-choice')]";

    // Select2 dropdown (global when open) - try both -active and plain for compatibility
    public static final String SELECT2_SEARCH_INPUT = ".select2-drop-active .select2-search input.select2-input, .select2-drop:not(.select2-display-none) .select2-search input.select2-input";
    public static final String SELECT2_RESULTS = ".select2-drop-active .select2-results li.select2-result-selectable, .select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable";
    public static final String SELECT2_NO_RESULTS = ".select2-drop-active .select2-results li.select2-no-results, .select2-drop:not(.select2-display-none) .select2-results li.select2-no-results";
    public static final String NOT_LISTED = XP + "//li[contains(@class,'select2-result')][contains(.,'Not Listed')]";

    // Diagnosis - form-diagnosis-codes section (stable class)
    public static final String DIAGNOSIS_SELECT2_LAST = XP + "//div[contains(@class,'form-diagnosis-codes')]//div[contains(@class,'select2-container')][last()]//a[contains(@class,'select2-choice')]";

    // Move to Issue — Issue Type / Issue Comment (visible after Move to Issue is clicked)
    public static final String ISSUE_TYPE = "#s2id_codingissuetype";
    public static final String ISSUE_TYPE_CHOICE = ISSUE_TYPE + " .select2-choice";
    public static final String ISSUE_TYPE_CHOSEN = ISSUE_TYPE + " .select2-chosen";
    public static final String ISSUE_TYPE_SELECT = "#codingissuetype, select[name='typeId']";
    public static final String ISSUE_COMMENT =
	    "div[ng-controller='Coding.Form.IssueController'] textarea[name='comment']";
}
