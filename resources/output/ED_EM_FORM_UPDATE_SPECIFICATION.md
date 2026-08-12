# ED EM Supplemental Form — Field Update Specification

**Reference:** `resources/output/autoCoderForm.html`

HTML structure and what to select for each field, based on `output.json` and `ED_EM Supplemental Tool.xlsx` (COPA, DATA, RISK sheets).

---

## 1. PROBLEM(S) ADDRESSED

*HTML: `<fieldset><legend><b>PROBLEM(S) ADDRESSED</b></legend> ...`*

| HTML Label (as shown on form) | Selection | HTML Element |
|------------------------------|-----------|--------------|
| **Self-Limited / Minor Problem(s)** | Select **0** \| **1** \| **2** | Radio group, `name="problem.self_limited_minor_problems"` |
| **Stable Chronic Illness** | Select **0** \| **1** \| **2** | Radio group, `name="problem.stable_chronic"` |
| **Stable Acute Illness** | Select this (checkbox) | Checkbox, `name="problem.stable_acute"` |
| **Acute, Uncomplicated Illness or Injury** | Select this (checkbox) | Checkbox, `name="problem.acute_uncomplicated"` |
| — — — — — — — — — — — — — — — — — — — — — — — — | *(divider)* | |
| **Chronic Illness w/ Exacerbation, Progression, or Side Effects of Treatment** | Select this (checkbox) | Checkbox, `name="problem.chronic_exac_progr_side_effects"` |
| **Acute, Complicated Injury** | Select this (checkbox) | Checkbox, `name="problem.acute_complicated"` |
| **Acute Illness w/ Systemic Symptoms** | Select this (checkbox) | Checkbox, `name="problem.acute_systemic_symptoms"` |
| **Undiagnosed New Problem w/ Uncertain Prognosis** | Select this (checkbox) | Checkbox, `name="problem.undiag_prob_uncertain_prog"` |
| — — — — — — — — — — — — — — — — — — — — — — — — | *(divider)* | |
| **Chronic Illness(es) with Severe Exacerbation, Progression, or SE of Treatment** | Select this (checkbox) | Checkbox, `name="problem.chronic_severe_exab_progr_se"` |
| **Acute or Chronic Illness/Injury Posing Threat to Life or Bodily Function** | Select this (checkbox) | Checkbox, `name="problem.acute_or_chronic_threat_to_life_and_body"` |

---

## 2. DATA

*HTML: `<fieldset><legend><b>DATA</b></legend> ...`*

### Bills Separate Interps?

*Sub-label: "Please check this clients coding considerations for EKG/Ultrasound/X-ray"*

### Top-level options

| HTML Label | Selection | HTML Element |
|------------|-----------|--------------|
| **Data N/A - Skipping data section as not needed for E/M** | Select this (checkbox) | Checkbox, `name="data.data_not_applicable"` |
| **No Data** | Select this (checkbox) | Checkbox, `name="data.minimal_or_none"` |
| **Review of External Notes** | Select **0** \| **1** \| **2** \| **3** | Radio group, `name="data.review_of_external_notes"` |

### Order and/or Review of Tests

*Sub-section header*

| HTML Label | Selection | HTML Element |
|------------|-----------|--------------|
| **Rhythm Strip** | Select this (checkbox) | Checkbox, `name="data.order_ekg_rhythm_strip"` |
| **EKG** | Select **0** \| **1** \| **2** \| **3** | Radio group, `name="data.order_review_of_test_results_ekg"` |
| **All Other Rad (XR/CT/MR/NM)** | Select **0** \| **1** \| **2** \| **3** | Radio group, `name="data.order_review_of_test_results_xray"` |
| **US** | Select **0** \| **1** \| **2** \| **3** | Radio group, `name="data.order_review_of_test_results_poc_us"` |
| **Labs** | Select **0** \| **1** \| **2** \| **3** | Radio group, `name="data.order_review_of_test_results_other"` |

### Independent Interpretation of Tests

*Sub-section header*

| HTML Label | Selection | HTML Element |
|------------|-----------|--------------|
| **Rhythm Strip** | Select this (checkbox) | Checkbox, `name="data.interpret_ekg_rhythm_strip"` |
| **EKG** | Select **0** \| **1** \| **2** \| **3** | Radio group, `name="data.indep_interpret_of_test_by_another_ekg"` |
| **All Other Rad (XR/CT/MR/NM)** | Select **0** \| **1** \| **2** \| **3** | Radio group, `name="data.indep_interpret_of_test_by_another_xray"` |
| **US** | Select **0** \| **1** \| **2** \| **3** | Radio group, `name="data.indep_interpret_of_test_by_another_poc_us"` |

### Other data options

| HTML Label | Selection | HTML Element |
|------------|-----------|--------------|
| **Assessment Requiring Independent Historian** | Select this (checkbox) | Checkbox, `name="data.assessment_with_indep_historian"` |
| **Discussion of Management or Test Intepretation w/ External Provider** | Select this (checkbox) | Checkbox, `name="data.discuss_mgnt_or_test_interpret_with_another"` |

---

## 3. RISK

*HTML: `<fieldset><legend><b>RISK</b></legend> ...`*

### Minimal risk

| HTML Label | Selection | HTML Element |
|------------|-----------|--------------|
| **Minimal** | Select this (checkbox) | Checkbox, `name="risk.minimal"` |
| **Rest, Gargle, Bandages, or Dressings** | Select this (checkbox) | Checkbox, `name="risk.rest_gargle_bandage_dressing"` |
| **Simple Localized Rash or Insect Bite (No Meds and No Fever)** | Select this (checkbox) | Checkbox, `name="risk.localized_rash_insect_bite"` |

— — — — — — — — — — — — — — — — — — — — — — — —

### Low risk

| HTML Label | Selection | HTML Element |
|------------|-----------|--------------|
| **OTC medications** | Select this (checkbox) | Checkbox, `name="risk.otc_medications"` |
| **Minor Surgery w/ No Risk Factors** | Select this (checkbox) | Checkbox, `name="risk.minor_surgery_with_no_risk"` |
| **Misc Procedures or Treatments in ED** (enema, hair tourniquet removal, earring removal) | Select this (checkbox) | Checkbox, `name="risk.misc_proc_or_treatments"` |
| **Specific Follow-up Instructions with External Provider Given** | Select this (checkbox) | Checkbox, `name="risk.specific_follow_up_with_external_provider_given"` |
| **Radiation exposure from extremity x-ray** | Select this (checkbox) | Checkbox, `name="risk.rad_exposure_extremity_xr"` |
| **Throat or Nasal Swab for Diagnostic Testing** | Select this (checkbox) | Checkbox, `name="risk.throat_or_nasal_swab_diagnostic_testing"` |

— — — — — — — — — — — — — — — — — — — — — — — —

### Moderate risk

| HTML Label | Selection | HTML Element |
|------------|-----------|--------------|
| **Prescription Drugs** | Select this (checkbox) | Checkbox, `name="risk.prescription_drugs"` |
| **IV Fluids w/ or w/o Additives** | Select this (checkbox) | Checkbox, `name="risk.iv_fluids_with_or_without_additives"` |
| **Minor Surgery w/ Risk Factors** | Select this (checkbox) | Checkbox, `name="risk.minor_surgery_with_risk_factors"` |
| **Major Surgery w/ No Risk Factors** | Select this (checkbox) | Checkbox, `name="risk.major_surgery_with_no_risk"` |
| **Diagnosis or Treatment Significantly Limited by Social Determinants of Health** | Select this (checkbox) | Checkbox, `name="risk.diag_treat_limited_by_social_det_health"` |
| **Radiation exposure from any CT scan or x-ray of Head, Neck, or Torso** | Select this (checkbox) | Checkbox, `name="risk.rad_exposure_ct_or_xr_head_neck_torso"` |
| **Rigid Musculoskeletal Immobilization** (e.g. splint or cast) | Select this (checkbox) | Checkbox, `name="risk.rigid_musculoskeletal_immobilization"` |
| **Infant OTC Meds** | Select this (checkbox) | Checkbox, `name="risk.infant_otc_meds"` |

— — — — — — — — — — — — — — — — — — — — — — — —

### High risk

| HTML Label | Selection | HTML Element |
|------------|-----------|--------------|
| **Drug Therapy Requiring Monitoring for Toxicity** | Select this (checkbox) | Checkbox, `name="risk.drug_therapy_req_monitor_toxicity"` |
| **Parenteral Controlled Substances** | Select this (checkbox) | Checkbox, `name="risk.parenteral_drugs"` |
| **Major Surgery w/ Risk Factors** | Select this (checkbox) | Checkbox, `name="risk.major_surgery_with_risk_factors"` |
| **Emergency Major Surgery** | Select this (checkbox) | Checkbox, `name="risk.emergency_major_surgery"` |
| **Decision regarding Hospitalization or Escalation of Hospital Care** | Select this (checkbox) | Checkbox, `name="risk.hospitalization_esc_of_hospital_care"` |
| **Decision not to Resuscitate or De-escalate Care Due to Poor Prognosis** | Select this (checkbox) | Checkbox, `name="risk.dnr_deescalate_care_poor_prog"` |
| **CT scan w/ IV contrast** | Select this (checkbox) | Checkbox, `name="risk.ct_scan_with_iv_contrast"` |
| **Category D Pregnancy Medications** | Select this (checkbox) | Checkbox, `name="risk.category_d_pregnancy_medications"` |
| **Administration of Moderate Sedation** | Select this (checkbox) | Checkbox, `name="risk.admin_of_moderate_sedation"` |
| **Physical restraints** | Select this (checkbox) | Checkbox, `name="risk.physical_restraints"` |
| **IV Anticoagulation Therapy** | Select this (checkbox) | Checkbox, `name="risk.iv_anticoagulation_therapy"` |

---

## 4. Options

| HTML Label | Selection | HTML Element |
|------------|-----------|--------------|
| **Skip - I am unable to use this form to derive the EM-CPT** | Select this (checkbox) | Checkbox, `name="options.skip_form_submission"` |

---

## 5. Example: Diverticulitis Case

For `output.json` with Diverticulitis, CT Abdomen Pelvis W IV, labs, IV fluids, IV pain meds:

**PROBLEM(S) ADDRESSED**
- Acute Illness w/ Systemic Symptoms : **select this**

**DATA**
- Review of External Notes : **select 2**
- Order and/or Review of Tests → All Other Rad (XR/CT/MR/NM) : **select 1**
- Order and/or Review of Tests → Labs : **select 3**
- Independent Interpretation of Tests → All Other Rad (XR/CT/MR/NM) : **select 2**

**RISK**
- Rest, Gargle, Bandages, or Dressings : *do not select*
- Prescription Drugs : **select this**
- IV Fluids w/ or w/o Additives : **select this**
- Radiation exposure from any CT scan or x-ray of Head, Neck, or Torso : **select this**

---

## 6. HTML selectors (for automation)

| Element Type | Action |
|--------------|--------|
| **Radio** | Click `input[name="..."][value="X"]` where X = 0, 1, 2, or 3 |
| **Checkbox** | Check = set `checked`; Uncheck = remove `checked` |
