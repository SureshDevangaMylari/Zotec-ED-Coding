package com.wl.zotecAgent.edem;

import com.wl.util.JsonReadService;

import java.util.Map;
import java.util.Objects;

/**
 * Main entry point: reads output.json and ED_EM Supplemental Tool.xlsx,
 * runs decision logic, and prints which form fields would be selected.
 * Does NOT update the HTML page - prints only.
 */
public class ED_EMSupplementalMain {

    /** HTML labels and IDs from autoCoderForm.html */
    private static final Map<String, String> HTML_LABELS = Map.ofEntries(
        Map.entry("problem.self_limited_minor_problems", "Self-Limited / Minor Problem(s)"),
        Map.entry("problem.stable_chronic", "Stable Chronic Illness"),
        Map.entry("problem.stable_acute", "Stable Acute Illness"),
        Map.entry("problem.acute_uncomplicated", "Acute, Uncomplicated Illness or Injury"),
        Map.entry("problem.chronic_exac_progr_side_effects", "Chronic Illness w/ Exacerbation, Progression, or Side Effects of Treatment"),
        Map.entry("problem.acute_complicated", "Acute, Complicated Injury"),
        Map.entry("problem.acute_systemic_symptoms", "Acute Illness w/ Systemic Symptoms"),
        Map.entry("problem.undiag_prob_uncertain_prog", "Undiagnosed New Problem w/ Uncertain Prognosis"),
        Map.entry("problem.chronic_severe_exab_progr_se", "Chronic Illness(es) with Severe Exacerbation, Progression, or SE of Treatment"),
        Map.entry("problem.acute_or_chronic_threat_to_life_and_body", "Acute or Chronic Illness/Injury Posing Threat to Life or Bodily Function"),
        Map.entry("data.data_not_applicable", "Data N/A - Skipping data section as not needed for E/M"),
        Map.entry("data.minimal_or_none", "No Data"),
        Map.entry("data.review_of_external_notes", "Review of External Notes"),
        Map.entry("data.order_ekg_rhythm_strip", "Rhythm Strip"),
        Map.entry("data.order_review_of_test_results_ekg", "EKG"),
        Map.entry("data.order_review_of_test_results_xray", "All Other Rad (XR/CT/MR/NM)"),
        Map.entry("data.order_review_of_test_results_poc_us", "US"),
        Map.entry("data.order_review_of_test_results_other", "Labs"),
        Map.entry("data.interpret_ekg_rhythm_strip", "Rhythm Strip"),
        Map.entry("data.indep_interpret_of_test_by_another_ekg", "EKG"),
        Map.entry("data.indep_interpret_of_test_by_another_xray", "All Other Rad (XR/CT/MR/NM)"),
        Map.entry("data.indep_interpret_of_test_by_another_poc_us", "US"),
        Map.entry("data.assessment_with_indep_historian", "Assessment Requiring Independent Historian"),
        Map.entry("data.discuss_mgnt_or_test_interpret_with_another", "Discussion of Management or Test Interpretation w/ External Provider"),
        Map.entry("risk.minimal", "Minimal"),
        Map.entry("risk.rest_gargle_bandage_dressing", "Rest, Gargle, Bandages, or Dressings"),
        Map.entry("risk.localized_rash_insect_bite", "Simple Localized Rash or Insect Bite (No Meds and No Fever)"),
        Map.entry("risk.otc_medications", "OTC medications"),
        Map.entry("risk.rad_exposure_extremity_xr", "Radiation exposure from extremity x-ray"),
        Map.entry("risk.prescription_drugs", "Prescription Drugs"),
        Map.entry("risk.iv_fluids_with_or_without_additives", "IV Fluids w/ or w/o Additives"),
        Map.entry("risk.rad_exposure_ct_or_xr_head_neck_torso", "Radiation exposure from any CT scan or x-ray of Head, Neck, or Torso"),
        Map.entry("risk.rigid_musculoskeletal_immobilization", "Rigid Musculoskeletal Immobilization (e.g. splint or cast)"),
        Map.entry("risk.ct_scan_with_iv_contrast", "CT scan w/ IV contrast")
    );

    private static final String ID_PREFIX = "jsonform-2-elt-";

    public static void main(String[] args) {
        JsonReadService reader = new JsonReadService();
        Map<String, Object> data = reader.readOutputJson();

        ED_EMExcelReader excelReader = new ED_EMExcelReader();
        ED_EMFormDecisionService decisionService = new ED_EMFormDecisionService(excelReader);
        Map<String, Object> selections = decisionService.decideSelections(data);

        System.out.println("=== What to UPDATE on the form (from output.json + ED_EM Supplemental Tool.xlsx) ===\n");
        System.out.println("Based on output.json and Excel (COPA, DATA, RISK sheets), the following fields have to be UPDATED:\n");

        System.out.println("--- PROBLEM(S) ADDRESSED (COPA sheet) | <fieldset><legend><b>PROBLEM(S) ADDRESSED</b></legend> ---");
        selections.entrySet().stream()
                .filter(e -> e.getKey().startsWith("problem.") && needsUpdate(e.getKey(), e.getValue()))
                .forEach(e -> printWithHtmlDetails(e.getKey(), e.getValue()));

        System.out.println("\n--- DATA (DATA sheet) | <fieldset><legend><b>DATA</b></legend> ---");
        System.out.println("  Sub-section: Order and/or Review of Tests");
        System.out.println("  Sub-section: Independent Interpretation of Tests");
        selections.entrySet().stream()
                .filter(e -> e.getKey().startsWith("data.") && needsUpdate(e.getKey(), e.getValue()))
                .forEach(e -> printWithHtmlDetails(e.getKey(), e.getValue()));

        System.out.println("\n--- RISK (RISK sheet) | <fieldset><legend><b>RISK</b></legend> ---");
        selections.entrySet().stream()
                .filter(e -> e.getKey().startsWith("risk.") && needsUpdate(e.getKey(), e.getValue()))
                .forEach(e -> printWithHtmlDetails(e.getKey(), e.getValue()));

        long count = selections.entrySet().stream()
                .filter(e -> needsUpdate(e.getKey(), e.getValue()))
                .count();
        if (count == 0) {
            System.out.println("  (no updates needed based on current output.json)");
        }
    }

    private static void printWithHtmlDetails(String key, Object value) {
        String label = HTML_LABELS.getOrDefault(key, key);
        String id = ID_PREFIX + key;
        String action = formatUpdateVal(value);
        System.out.println("  " + label + " : " + action);
        if (value instanceof String s) {
            System.out.println("    input[name=\"" + key + "\"][value=\"" + s + "\"]  id=\"" + id + "\"");
        } else {
            System.out.println("    input[name=\"" + key + "\"]  id=\"" + id + "\"  (checkbox)");
        }
    }

    /** Only fields that differ from form default need updating. */
    private static boolean needsUpdate(String key, Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;  // checkbox: only update when checked
        if (value instanceof String s) {
            if (key.contains("self_limited") || key.contains("stable_chronic"))
                return !"0".equals(s);  // radio: update when not 0
            if (key.startsWith("data."))
                return !"0".equals(s);  // data radios: update when not 0
        }
        return false;
    }

    private static String formatUpdateVal(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean b) return b ? "check (select this)" : "uncheck";
        if (v instanceof String s) return "select " + s;
        return Objects.toString(v);
    }
}
