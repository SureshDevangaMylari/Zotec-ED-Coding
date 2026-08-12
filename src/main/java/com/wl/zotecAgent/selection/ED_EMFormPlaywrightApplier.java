package com.wl.zotecAgent.selection;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.wl.zotecAgent.PlayTestActionLog;
import com.wl.zotecAgent.ResumePayloadMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Applies the same selection map as {@link ED_EMFormHtmlUpdater} to the live ED EM Supplemental form in the
 * browser (Playwright {@link Page}), instead of mutating a saved {@code autoCoderForm.html} file.
 * <p>
 * Uses UI patterns recorded from the live Zotec form: {@code [id="jsonform-&lt;n&gt;-elt-&lt;field.name&gt;"]}
 * groups for radios (label / option text clicks) and label-based clicks for Bootstrap-styled checkboxes, with
 * {@code input} check/setChecked as fallback.
 */
public final class ED_EMFormPlaywrightApplier {

    private static final Logger log = LoggerFactory.getLogger(ED_EMFormPlaywrightApplier.class);

    private static final String FORM_IN_MODAL = "#codingAssistantBody #autoCoderForm";
    private static final String FORM_FALLBACK = "#autoCoderForm";

    /**
     * Visible label substrings for checkbox clicks (fallback when {@code label:has(input)} is not used).
     * Aligned with live modal HTML: {@code #codingAssistantBody} / {@code #autoCoderForm} (jsonform-2-elt-* in DOM).
     */
    private static final Map<String, String> CHECKBOX_CLICK_TEXT = Map.ofEntries(
	    Map.entry("options.skip_form_submission", "Skip - I am unable"),
	    Map.entry("problem.stable_acute", "Stable Acute Illness"),
	    Map.entry("problem.acute_uncomplicated", "Acute, Uncomplicated Illness or Injury"),
	    Map.entry("problem.chronic_exac_progr_side_effects", "Chronic Illness w/ Exacerbation"),
	    Map.entry("problem.acute_complicated", "Acute, Complicated Injury"),
	    Map.entry("problem.acute_systemic_symptoms", "Acute Illness w/ Systemic Symptoms"),
	    Map.entry("problem.undiag_prob_uncertain_prog", "Undiagnosed New Problem w/ Uncertain Prognosis"),
	    Map.entry("problem.chronic_severe_exab_progr_se", "Chronic Illness(es) with Severe Exacerbation"),
	    Map.entry("problem.acute_or_chronic_threat_to_life_and_body",
		    "Acute or Chronic Illness/Injury Posing Threat"),
	    Map.entry("data.data_not_applicable", "Data N/A - Skipping data section"),
	    Map.entry("data.minimal_or_none", "No Data"),
	    Map.entry("data.order_ekg_rhythm_strip", "Rhythm Strip"),
	    Map.entry("data.interpret_ekg_rhythm_strip", "Rhythm Strip"),
	    Map.entry("data.assessment_with_indep_historian", "Assessment Requiring Independent Historian"),
	    Map.entry("data.discuss_mgnt_or_test_interpret_with_another",
		    "Discussion of Management or Test Intepretation"),
	    Map.entry("risk.minimal", "Minimal"),
	    Map.entry("risk.rest_gargle_bandage_dressing", "Rest, Gargle, Bandages, or Dressings"),
	    Map.entry("risk.localized_rash_insect_bite", "Simple Localized Rash or Insect Bite"),
	    Map.entry("risk.otc_medications", "OTC medications"),
	    Map.entry("risk.minor_surgery_with_no_risk", "Minor Surgery w/ No Risk Factors"),
	    Map.entry("risk.misc_proc_or_treatments", "Misc Procedures or Treatments in ED"),
	    Map.entry("risk.specific_follow_up_with_external_provider_given",
		    "Specific Follow-up Instructions with External Provider"),
	    Map.entry("risk.rad_exposure_extremity_xr", "Radiation exposure from extremity x-ray"),
	    Map.entry("risk.throat_or_nasal_swab_diagnostic_testing", "Throat or Nasal Swab for Diagnostic Testing"),
	    Map.entry("risk.prescription_drugs", "Prescription Drugs"),
	    Map.entry("risk.iv_fluids_with_or_without_additives", "IV Fluids w/ or w/o Additives"),
	    Map.entry("risk.minor_surgery_with_risk_factors", "Minor Surgery w/ Risk Factors"),
	    Map.entry("risk.major_surgery_with_no_risk", "Major Surgery w/ No Risk Factors"),
	    Map.entry("risk.diag_treat_limited_by_social_det_health",
		    "Diagnosis or Treatment Significantly Limited by Social Determinants"),
	    Map.entry("risk.rad_exposure_ct_or_xr_head_neck_torso",
		    "Radiation exposure from any CT scan or x-ray of Head"),
	    Map.entry("risk.rigid_musculoskeletal_immobilization",
		    "Rigid Musculoskeletal Immobilization (e.g. splint or cast)"),
	    Map.entry("risk.infant_otc_meds", "Infant OTC Meds"),
	    Map.entry("risk.drug_therapy_req_monitor_toxicity", "Drug Therapy Requiring Monitoring for Toxicity"),
	    Map.entry("risk.parenteral_drugs", "Parenteral Controlled Substances"),
	    Map.entry("risk.major_surgery_with_risk_factors", "Major Surgery w/ Risk Factors"),
	    Map.entry("risk.emergency_major_surgery", "Emergency Major Surgery"),
	    Map.entry("risk.hospitalization_esc_of_hospital_care",
		    "Decision regarding Hospitalization or Escalation of Hospital Care"),
	    Map.entry("risk.dnr_deescalate_care_poor_prog",
		    "Decision not to Resuscitate or De-escalate Care Due to Poor Prognosis"),
	    Map.entry("risk.ct_scan_with_iv_contrast", "CT scan w/ IV contrast"),
	    Map.entry("risk.category_d_pregnancy_medications", "Category D Pregnancy Medications"),
	    Map.entry("risk.admin_of_moderate_sedation", "Administration of Moderate Sedation"),
	    Map.entry("risk.physical_restraints", "Physical restraints"),
	    Map.entry("risk.iv_anticoagulation_therapy", "IV Anticoagulation Therapy"));

    private static final int CLICK_TIMEOUT_MS = 8_000;

    private static final String CRITICAL_CARE_MARKER = "Critical Care Time Requirements:";

    private ED_EMFormPlaywrightApplier() {}

    /**
     * True when the ED supplemental form shows the Critical Care panel
     * (visible text {@code Critical Care Time Requirements:}).
     */
    public static boolean isCriticalCareFormVisible(Page page) {
	if (page == null) {
	    return false;
	}
	try {
	    Locator marker = page.getByText(CRITICAL_CARE_MARKER);
	    return marker.count() > 0 && marker.first().isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    /** Closes the coding-assistant ED modal if open so Skip on the workfile is usable. */
    public static void dismissEdModalIfOpen(Page page) {
	if (page == null) {
	    return;
	}
	try {
	    Locator body = page.locator("#codingAssistantBody");
	    if (body.count() == 0 || !body.first().isVisible()) {
		return;
	    }
	    Locator close = page.locator(
		    "#codingAssistantBody button.close, .modal.in button.close, "
			    + "#codingAssistantBody [ng-click*='cancel'], #codingAssistantBody [ng-click*='close']")
		    .first();
	    if (close.count() > 0 && close.isVisible()) {
		close.click(new Locator.ClickOptions().setForce(true));
	    } else {
		page.keyboard().press("Escape");
	    }
	    Thread.sleep(200);
	} catch (Exception e) {
	    log.debug("dismissEdModalIfOpen: {}", e.getMessage());
	    try {
		page.keyboard().press("Escape");
	    } catch (Exception ignored) {
	    }
	}
    }

    /**
     * Chooses EM vs Critical Care from resume JSON and applies the matching ED supplemental form.
     */
    public static void applyEdFormFromResume(Page page, Map<String, Object> resumePayload) {
	if (page == null) {
	    return;
	}
	if (ResumePayloadMapper.shouldUseCriticalCareForm(resumePayload)) {
	    Map<String, Object> cc = ResumePayloadMapper.toCriticalCareSelections(resumePayload);
	    log.info("ED form: Critical Care path ({} field(s))", cc.size());
	    applyCriticalCareSelections(page, cc);
	} else {
	    Map<String, Object> em = ResumePayloadMapper.toEdSelections(resumePayload);
	    log.info("ED form: EM Level path ({} field(s))", em.size());
	    ensureEmLevelBillingSelected(page);
	    applySelections(page, em);
	}
    }

    /** Selects the Critical Care billing tab (#cc-btn) and waits for the CC panel. */
    public static void ensureCriticalCareBillingSelected(Page page) {
	try {
	    if (isCriticalCareFormVisible(page)) {
		return;
	    }
	    Locator ccBtn = page.locator("#cc-btn");
	    if (ccBtn.count() > 0 && ccBtn.first().isVisible()) {
		PlayTestActionLog.update("ED billing", "Critical Care");
		ccBtn.first().click(new Locator.ClickOptions().setForce(true));
		Thread.sleep(300);
	    } else {
		Locator select = page.locator(
			".hidden-selectfieldset select, select[name*='billing'], select.nav.form-control").first();
		if (select.count() > 0) {
		    select.selectOption("critical_care");
		    select.evaluate("el => el.dispatchEvent(new Event('change', { bubbles: true }))");
		    Thread.sleep(300);
		}
	    }
	    // Wait briefly for Critical Care panel
	    for (int i = 0; i < 15 && !isCriticalCareFormVisible(page); i++) {
		Thread.sleep(200);
	    }
	} catch (Exception e) {
	    log.warn("ensureCriticalCareBillingSelected: {}", e.getMessage());
	}
    }

    /** Selects the EM Level billing tab when present. */
    public static void ensureEmLevelBillingSelected(Page page) {
	try {
	    Locator emBtn = page.locator("#em-btn");
	    if (emBtn.count() > 0 && emBtn.first().isVisible()) {
		emBtn.first().click(new Locator.ClickOptions().setForce(true));
		Thread.sleep(200);
	    }
	} catch (Exception e) {
	    log.debug("ensureEmLevelBillingSelected: {}", e.getMessage());
	}
    }

    /**
     * Fills Critical Care Time / Illness / Intervention from flattened selection map.
     */
    public static void applyCriticalCareSelections(Page page, Map<String, Object> selections) {
	ensureCriticalCareBillingSelected(page);
	Locator form = resolveForm(page);
	if (form.count() == 0) {
	    log.warn("ED Critical Care form (#autoCoderForm) not found");
	    PlayTestActionLog.skip("ED Critical Care", "form not found");
	    return;
	}
	Locator scoped = form.first();
	ensureFormReady(scoped);
	String jsonformPrefix = detectJsonformEltPrefix(scoped);

	if (selections == null || selections.isEmpty()) {
	    PlayTestActionLog.noData("ED Critical Care selections");
	    return;
	}
	PlayTestActionLog.step("ED Critical Care applySelections (" + selections.size() + " field(s))");

	for (Map.Entry<String, Object> e : selections.entrySet()) {
	    String name = e.getKey();
	    Object value = e.getValue();
	    if (name == null || value == null) {
		continue;
	    }
	    try {
		if (value instanceof Boolean b) {
		    if (b) {
			applyCheckbox(scoped, name, true);
		    }
		} else {
		    String s = String.valueOf(value).trim();
		    if (s.isEmpty()) {
			continue;
		    }
		    if (scoped.locator("input[type=radio][name='" + name + "']").count() > 0) {
			applyRadio(scoped, jsonformPrefix, name, s);
		    } else if (scoped.locator("select[name='" + name + "']").count() > 0) {
			applySelect(scoped, name, s);
		    } else if (scoped.locator(
			    "input[type=text][name='" + name + "'], textarea[name='" + name + "']").count() > 0) {
			applyTextInput(scoped, name, s);
		    } else {
			PlayTestActionLog.skip("ED." + name, "control not found for '" + s + "'");
		    }
		}
	    } catch (Exception ex) {
		log.debug("Could not apply CC selection {}: {}", name, ex.getMessage());
		PlayTestActionLog.skip("ED." + name, ex.getMessage());
	    }
	    sleep(50);
	}
    }

    private static void applySelect(Locator form, String name, String value) {
	Locator select = form.locator("select[name='" + name + "']").first();
	if (select.count() == 0) {
	    PlayTestActionLog.skip("ED." + name, "select not found");
	    return;
	}
	scrollIntoView(select);
	try {
	    select.selectOption(value);
	    PlayTestActionLog.update("ED." + name, "select -> " + value);
	} catch (Exception e) {
	    // try matching option by visible text containing value
	    try {
		Locator opt = select.locator("option").filter(new Locator.FilterOptions().setHasText(value));
		if (opt.count() > 0) {
		    String optVal = opt.first().getAttribute("value");
		    if (optVal != null && !optVal.isBlank()) {
			select.selectOption(optVal);
			PlayTestActionLog.update("ED." + name, "select -> " + optVal);
			return;
		    }
		}
	    } catch (Exception ignored) {
	    }
	    PlayTestActionLog.skip("ED." + name, "select option '" + value + "' failed: " + e.getMessage());
	}
    }

    private static void applyTextInput(Locator form, String name, String value) {
	Locator input = form.locator(
		"input[type=text][name='" + name + "'], textarea[name='" + name + "']").first();
	if (input.count() == 0) {
	    PlayTestActionLog.skip("ED." + name, "text input not found");
	    return;
	}
	scrollIntoView(input);
	input.fill(value);
	PlayTestActionLog.update("ED." + name, "text -> " + value);
    }

    /**
     * Sets radios (values {@code 0}–{@code 3}) and checkboxes on {@code #autoCoderForm} in the current page.
     */
    public static void applySelections(Page page, Map<String, Object> selections) {
	if (page == null || selections == null || selections.isEmpty()) {
	    PlayTestActionLog.noData("ED EM selections");
	    return;
	}
	PlayTestActionLog.step("ED EM form applySelections (" + selections.size() + " field(s))");
	Locator form = resolveForm(page);
	if (form.count() == 0) {
	    log.warn("ED EM Supplemental form (#autoCoderForm) not found on page");
	    PlayTestActionLog.skip("ED EM form", "form not found on page");
	    return;
	}
	Locator scoped = form.first();
	ensureFormReady(scoped);
	String jsonformPrefix = detectJsonformEltPrefix(scoped);
	for (Map.Entry<String, Object> e : selections.entrySet()) {
	    String name = e.getKey();
	    Object value = e.getValue();
	    if (value == null) {
		continue;
	    }
	    try {
		if (value instanceof Boolean b) {
		    applyCheckbox(scoped, name, b);
		} else {
		    String s = String.valueOf(value).trim();
		    if (s.matches("[0-3]")) {
			applyRadio(scoped, jsonformPrefix, name, s);
		    } else if (PlayTestActionLog.isEnabled()) {
			PlayTestActionLog.skip("ED." + name, "unrecognized value '" + s + "'");
		    }
		}
	    } catch (Exception ex) {
		log.debug("Could not apply selection {}: {}", name, ex.getMessage());
		PlayTestActionLog.skip("ED." + name, ex.getMessage());
	    }
	    sleep(50);
	}
    }

    private static Locator resolveForm(Page page) {
	Locator inModal = page.locator(FORM_IN_MODAL);
	if (inModal.count() > 0) {
	    return inModal;
	}
	return page.locator(FORM_FALLBACK);
    }

    /** Live form uses {@code jsonform-1-elt-…} or {@code jsonform-2-elt-…} depending on build. */
    private static String detectJsonformEltPrefix(Locator form) {
	if (form.locator("[id^='jsonform-1-elt-']").count() > 0) {
	    return "jsonform-1-elt-";
	}
	if (form.locator("[id^='jsonform-2-elt-']").count() > 0) {
	    return "jsonform-2-elt-";
	}
	return "jsonform-1-elt-";
    }

    private static void applyRadio(Locator form, String jsonformPrefix, String name, String value) {
	Locator fieldAnchor = form.locator("input[type=radio][name='" + name + "']").first();
	if (fieldAnchor.count() > 0) {
	    scrollIntoView(fieldAnchor);
	}

	String idValue = jsonformPrefix + name;
	Locator group = form.locator("[id=" + cssQuote(idValue) + "]");
	if (group.count() > 0) {
	    Locator byLabel = group.locator("label").filter(new Locator.FilterOptions().setHasText(value));
	    if (byLabel.count() > 0) {
		PlayTestActionLog.update("ED." + name, "radio -> " + value);
		clickLocator(byLabel.first());
		return;
	    }
	    Locator byExact = group.getByText(value, new Locator.GetByTextOptions().setExact(true));
	    if (byExact.count() > 0) {
		PlayTestActionLog.update("ED." + name, "radio -> " + value);
		clickLocator(byExact.first());
		return;
	    }
	    Locator byLoose = group.getByText(value);
	    if (byLoose.count() > 0) {
		PlayTestActionLog.update("ED." + name, "radio -> " + value);
		clickLocator(byLoose.first());
		return;
	    }
	}

	Locator radioLabel = form.locator(
		"label:has(input[type=radio][name='" + name + "'][value='" + value + "'])");
	if (radioLabel.count() > 0) {
	    PlayTestActionLog.update("ED." + name, "radio -> " + value);
	    clickLocator(radioLabel.first());
	    return;
	}

	Locator radio = form.locator("input[type=radio][name='" + name + "'][value='" + value + "']");
	if (radio.count() == 0) {
	    log.debug("Radio not found: {} value {}", name, value);
	    PlayTestActionLog.skip("ED." + name, "radio value " + value + " not found");
	    return;
	}
	PlayTestActionLog.update("ED." + name, "radio -> " + value);
	setRadioChecked(radio.first());
    }

    /**
     * Prefer clicking the real {@code <label>} that wraps the checkbox (matches live modal DOM); avoids
     * ambiguous {@code getByText("Rhythm Strip")} when two checkboxes share the same visible text.
     */
    private static void applyCheckbox(Locator form, String name, boolean checked) {
	Locator input = form.locator("input[type=checkbox][name='" + name + "']");
	if (input.count() == 0) {
	    log.debug("Checkbox input not found: {}", name);
	    PlayTestActionLog.skip("ED." + name, "checkbox not found");
	    return;
	}
	Locator firstIn = input.first();
	scrollIntoView(firstIn);
	try {
	    if (firstIn.isChecked() == checked) {
		PlayTestActionLog.skip("ED." + name, "already " + (checked ? "checked" : "unchecked"));
		return;
	    }
	} catch (Exception ignored) {
	}

	PlayTestActionLog.update("ED." + name, checked ? "check" : "uncheck");
	Locator labelWrap = form.locator("label:has(input[type=checkbox][name='" + name + "'])");
	if (labelWrap.count() > 0) {
	    try {
		clickLocator(labelWrap.first());
		if (firstIn.isChecked() == checked) {
		    return;
		}
		clickLocator(labelWrap.first());
		if (firstIn.isChecked() == checked) {
		    return;
		}
	    } catch (Exception ex) {
		log.debug("label click for checkbox {}: {}", name, ex.getMessage());
	    }
	}

	if (checked) {
	    String hint = checkboxClickText(name);
	    boolean clicked = false;
	    if (hint != null && !hint.isBlank()) {
		clicked = tryClickCheckboxByText(form, hint);
		if (!clicked) {
		    clicked = tryClickCheckboxByRole(form, hint);
		}
	    }
	    if (!clicked) {
		firstIn.setChecked(true, new Locator.SetCheckedOptions().setForce(true));
	    }
	} else {
	    firstIn.setChecked(false, new Locator.SetCheckedOptions().setForce(true));
	}
    }

    private static String checkboxClickText(String name) {
	String t = CHECKBOX_CLICK_TEXT.get(name);
	if (t != null) {
	    return t;
	}
	String label = FormFieldConstants.getLabel(name);
	if (label == null || label.equals(name)) {
	    return null;
	}
	int paren = label.indexOf('(');
	if (paren > 0) {
	    label = label.substring(0, paren).trim();
	}
	int comma = label.indexOf(',');
	if (comma > 0 && comma < 48) {
	    return label.substring(0, comma).trim();
	}
	return label.length() > 52 ? label.substring(0, 52).trim() : label;
    }

    private static boolean tryClickCheckboxByText(Locator form, String hint) {
	try {
	    Pattern asSubstring = Pattern.compile(Pattern.quote(hint), Pattern.CASE_INSENSITIVE);
	    Locator hits = form.getByText(asSubstring);
	    if (hits.count() > 0) {
		clickLocator(hits.first());
		return true;
	    }
	} catch (Exception ignored) {
	}
	return false;
    }

    private static boolean tryClickCheckboxByRole(Locator form, String hint) {
	try {
	    int flags = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
	    Pattern namePat = Pattern.compile(".*" + Pattern.quote(hint) + ".*", flags);
	    Locator cb = form.getByRole(AriaRole.CHECKBOX, new Locator.GetByRoleOptions().setName(namePat));
	    if (cb.count() > 0) {
		scrollIntoView(cb.first());
		cb.first().check(new Locator.CheckOptions().setTimeout(CLICK_TIMEOUT_MS).setForce(true));
		return true;
	    }
	} catch (Exception ignored) {
	}
	return false;
    }

    /** ED form lives in a scrollable modal; elements may be visible but outside the viewport. */
    private static void ensureFormReady(Locator form) {
	try {
	    Page page = form.page();
	    Locator modalBody = page.locator("#codingAssistantBody");
	    if (modalBody.count() > 0) {
		modalBody.first().waitFor(new Locator.WaitForOptions()
			.setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
			.setTimeout(CLICK_TIMEOUT_MS));
	    }
	    form.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(CLICK_TIMEOUT_MS));
	} catch (Exception e) {
	    log.debug("ensureFormReady: {}", e.getMessage());
	}
    }

    private static void scrollIntoView(Locator target) {
	try {
	    target.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(CLICK_TIMEOUT_MS));
	    sleep(150);
	} catch (Exception e) {
	    log.debug("scrollIntoViewIfNeeded failed: {}", e.getMessage());
	}
    }

    private static void clickLocator(Locator target) {
	scrollIntoView(target);
	try {
	    target.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
	} catch (Exception e) {
	    log.debug("Normal click failed ({}), retrying with force", e.getMessage());
	    target.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS).setForce(true));
	}
    }

    private static void setRadioChecked(Locator radio) {
	scrollIntoView(radio);
	try {
	    if (radio.isChecked()) {
		return;
	    }
	} catch (Exception ignored) {
	}
	try {
	    radio.check(new Locator.CheckOptions().setTimeout(CLICK_TIMEOUT_MS).setForce(true));
	} catch (Exception e) {
	    log.debug("radio.check failed ({}), using setChecked force", e.getMessage());
	    radio.setChecked(true, new Locator.SetCheckedOptions().setForce(true).setTimeout(CLICK_TIMEOUT_MS));
	}
    }

    /** Attribute value for {@code [id=…]} when id contains dots. */
    private static String cssQuote(String s) {
	return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void sleep(int ms) {
	try {
	    Thread.sleep(ms);
	} catch (InterruptedException e) {
	    Thread.currentThread().interrupt();
	}
    }
}
