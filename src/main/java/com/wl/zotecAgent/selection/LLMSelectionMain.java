package com.wl.zotecAgent.selection;

import com.wl.util.JsonReadService;

import java.util.Map;
import java.util.Objects;

/**
 * Main entry point using LLM to decide ED EM form selections.
 * Reads output.json, calls LLM with Excel context, prints what to update.
 */
public class LLMSelectionMain {

    /** HTML labels from autoCoderForm.html */
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
        Map.entry("data.review_of_external_notes", "Review of External Notes"),
        Map.entry("data.order_review_of_test_results_ekg", "EKG"),
        Map.entry("data.order_review_of_test_results_xray", "All Other Rad (XR/CT/MR/NM)"),
        Map.entry("data.order_review_of_test_results_poc_us", "US"),
        Map.entry("data.order_review_of_test_results_other", "Labs"),
        Map.entry("data.indep_interpret_of_test_by_another_ekg", "EKG - Independent Interpretation"),
        Map.entry("data.indep_interpret_of_test_by_another_xray", "All Other Rad (XR/CT/MR/NM) - Independent Interpretation"),
        Map.entry("data.indep_interpret_of_test_by_another_poc_us", "US - Independent Interpretation"),
        Map.entry("risk.iv_fluids_with_or_without_additives", "IV Fluids w/ or w/o Additives"),
        Map.entry("risk.prescription_drugs", "Prescription Drugs"),
        Map.entry("risk.rad_exposure_ct_or_xr_head_neck_torso", "Radiation exposure from any CT scan or x-ray of Head, Neck, or Torso"),
        Map.entry("risk.ct_scan_with_iv_contrast", "CT scan w/ IV contrast"),
        Map.entry("risk.minimal", "Minimal"),
        Map.entry("risk.rest_gargle_bandage_dressing", "Rest, Gargle, Bandages, or Dressings"),
        Map.entry("risk.otc_medications", "OTC medications"),
        Map.entry("risk.rad_exposure_extremity_xr", "Radiation exposure from extremity x-ray"),
        Map.entry("risk.rigid_musculoskeletal_immobilization", "Rigid Musculoskeletal Immobilization (e.g. splint or cast)")
    );

    private static final String ID_PREFIX = "jsonform-2-elt-";

    public static void main(String[] args) {
        JsonReadService reader = new JsonReadService();
        Map<String, Object> data = reader.readOutputJson();

        LLMSelectionService llmService = new LLMSelectionService();
        Map<String, Object> selections = llmService.decideSelections(data);

        System.out.println("=== LLM-based ED EM Form Selections (from output.json + Excel) ===\n");
        System.out.println("LLM decided the following fields have to be UPDATED:\n");

        System.out.println("--- PROBLEM(S) ADDRESSED ---");
        selections.entrySet().stream()
                .filter(e -> e.getKey().startsWith("problem."))
                .forEach(e -> printWithHtmlDetails(e.getKey(), e.getValue()));

        System.out.println("\n--- DATA ---");
        selections.entrySet().stream()
                .filter(e -> e.getKey().startsWith("data."))
                .forEach(e -> printWithHtmlDetails(e.getKey(), e.getValue()));

        System.out.println("\n--- RISK ---");
        selections.entrySet().stream()
                .filter(e -> e.getKey().startsWith("risk."))
                .forEach(e -> printWithHtmlDetails(e.getKey(), e.getValue()));

        if (selections.isEmpty()) {
            System.out.println("  (no updates from LLM - check LLM availability or response format)");
        }
    }

    private static void printWithHtmlDetails(String key, Object value) {
        String label = HTML_LABELS.getOrDefault(key, key);
        String id = ID_PREFIX + key;
        String action = value instanceof Boolean b
                ? (b ? "check (select this)" : "uncheck")
                : "select " + value;
        System.out.println("  " + label + " : " + action);
        if (value instanceof String s) {
            System.out.println("    input[name=\"" + key + "\"][value=\"" + s + "\"]  id=\"" + id + "\"");
        } else {
            System.out.println("    input[name=\"" + key + "\"]  id=\"" + id + "\"  (checkbox)");
        }
    }
}
