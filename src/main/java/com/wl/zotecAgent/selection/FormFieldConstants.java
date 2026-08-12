package com.wl.zotecAgent.selection;

import java.util.Map;

/**
 * Exact HTML field names and full labels from resources/output/autoCoderForm.html.
 * Use these for printing and when updating the form.
 */
public final class FormFieldConstants {

    private static final String ID_PREFIX = "jsonform-2-elt-";

    /** name attribute -> full label text (as shown in HTML form) */
    public static final Map<String, String> NAME_TO_LABEL = Map.ofEntries(
        Map.entry("options.skip_form_submission", "Skip - I am unable to use this form to derive the EM-CPT"),
        // PROBLEM(S) ADDRESSED - Radios
        Map.entry("problem.self_limited_minor_problems", "Self-Limited / Minor Problem(s)"),
        Map.entry("problem.stable_chronic", "Stable Chronic Illness"),
        // PROBLEM - Checkboxes
        Map.entry("problem.stable_acute", "Stable Acute Illness"),
        Map.entry("problem.acute_uncomplicated", "Acute, Uncomplicated Illness or Injury"),
        Map.entry("problem.chronic_exac_progr_side_effects", "Chronic Illness w/ Exacerbation, Progression, or Side Effects of Treatment"),
        Map.entry("problem.acute_complicated", "Acute, Complicated Injury"),
        Map.entry("problem.acute_systemic_symptoms", "Acute Illness w/ Systemic Symptoms"),
        Map.entry("problem.undiag_prob_uncertain_prog", "Undiagnosed New Problem w/ Uncertain Prognosis"),
        Map.entry("problem.chronic_severe_exab_progr_se", "Chronic Illness(es) with Severe Exacerbation, Progression, or SE of Treatment"),
        Map.entry("problem.acute_or_chronic_threat_to_life_and_body", "Acute or Chronic Illness/Injury Posing Threat to Life or Bodily Function"),
        // DATA
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
        // RISK
        Map.entry("risk.minimal", "Minimal"),
        Map.entry("risk.rest_gargle_bandage_dressing", "Rest, Gargle, Bandages, or Dressings"),
        Map.entry("risk.localized_rash_insect_bite", "Simple Localized Rash or Insect Bite (No Meds and No Fever)"),
        Map.entry("risk.otc_medications", "OTC medications"),
        Map.entry("risk.minor_surgery_with_no_risk", "Minor Surgery w/ No Risk Factors"),
        Map.entry("risk.misc_proc_or_treatments", "Misc Procedures or Treatments in ED (e.g., enema, hair turnicate removal, earring removal)"),
        Map.entry("risk.specific_follow_up_with_external_provider_given", "Specific Follow-up Instructions with External Provider Given"),
        Map.entry("risk.rad_exposure_extremity_xr", "Radiation exposure from extremity x-ray"),
        Map.entry("risk.throat_or_nasal_swab_diagnostic_testing", "Throat or Nasal Swab for Diagnostic Testing"),
        Map.entry("risk.prescription_drugs", "Prescription Drugs"),
        Map.entry("risk.iv_fluids_with_or_without_additives", "IV Fluids w/ or w/o Additives"),
        Map.entry("risk.minor_surgery_with_risk_factors", "Minor Surgery w/ Risk Factors"),
        Map.entry("risk.major_surgery_with_no_risk", "Major Surgery w/ No Risk Factors"),
        Map.entry("risk.diag_treat_limited_by_social_det_health", "Diagnosis or Treatment Significantly Limited by Social Determinants of Health"),
        Map.entry("risk.rad_exposure_ct_or_xr_head_neck_torso", "Radiation exposure from any CT scan or x-ray of Head, Neck, or Torso"),
        Map.entry("risk.rigid_musculoskeletal_immobilization", "Rigid Musculoskeletal Immobilization (e.g. splint or cast)"),
        Map.entry("risk.infant_otc_meds", "Infant OTC Meds"),
        Map.entry("risk.drug_therapy_req_monitor_toxicity", "Drug Therapy Requiring Monitoring for Toxicity"),
        Map.entry("risk.parenteral_drugs", "Parenteral Controlled Substances"),
        Map.entry("risk.major_surgery_with_risk_factors", "Major Surgery w/ Risk Factors"),
        Map.entry("risk.emergency_major_surgery", "Emergency Major Surgery"),
        Map.entry("risk.hospitalization_esc_of_hospital_care", "Decision regarding Hospitalization or Escalation of Hospital Care"),
        Map.entry("risk.dnr_deescalate_care_poor_prog", "Decision not to Resuscitate or De-escalate Care Due to Poor Prognosis"),
        Map.entry("risk.ct_scan_with_iv_contrast", "CT scan w/ IV contrast"),
        Map.entry("risk.category_d_pregnancy_medications", "Category D Pregnancy Medications"),
        Map.entry("risk.admin_of_moderate_sedation", "Administration of Moderate Sedation"),
        Map.entry("risk.physical_restraints", "Physical restraints"),
        Map.entry("risk.iv_anticoagulation_therapy", "IV Anticoagulation Therapy")
    );

    public static String getLabel(String name) {
        return NAME_TO_LABEL.getOrDefault(name, name);
    }

    public static String getId(String name) {
        return ID_PREFIX + name;
    }
}
