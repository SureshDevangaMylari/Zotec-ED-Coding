package com.wl.zotecAgent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.wl.util.PlaywrightService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Fills the ED EM Supplemental Tool modal (#codingAssistantBody) using:
 * - output.json (extracted clinical data)
 * - ED_EM Supplemental Tool.xlsx (copa, data, risk sheets for Problem/Data/Risk levels)
 *
 * Form structure: problem.* (radio/checkbox), data.* (radio/checkbox), risk.* (checkbox)
 */
public class ED_EMSupplementalFormFiller {

    private static final Logger log = LogManager.getLogger(ED_EMSupplementalFormFiller.class);
    private static final String MODAL_BODY = "#codingAssistantBody";
    private static final String FORM_ID = "#autoCoderForm";

    private final PlaywrightService ps;
    private final ED_EMSupplementalExcelReader excelReader;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public ED_EMSupplementalFormFiller(Page page) {
        this.ps = new PlaywrightService(page);
        this.excelReader = new ED_EMSupplementalExcelReader();
    }

    /** Default: fill from resources/jsonfolder/output.json */
    public void fillFromOutputJson() {
        fillFromJsonFile("resources/jsonfolder/output.json");
    }

    /**
     * Load output.json and fill the supplemental form.
     */
    public void fillFromJsonFile(String jsonPath) {
        try {
            Path p = Path.of(jsonPath);
            if (!Files.exists(p))
                p = Path.of(System.getProperty("user.dir"), jsonPath);
            String content = Files.readString(p);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = jsonMapper.readValue(content, Map.class);
            fill(data);
        } catch (Exception e) {
            log.warn("Could not fill from {}: {}", jsonPath, e.getMessage());
        }
    }

    /**
     * Fill the form from extracted data map (output.json structure).
     */
    public void fill(Map<String, Object> extractedData) {
        if (extractedData == null || extractedData.isEmpty())
            return;
        Locator form = ps.locator(MODAL_BODY + " " + FORM_ID);
        if (form.count() == 0) {
            log.warn("ED EM Supplemental form not found");
            return;
        }

        Map<String, Object> selections = buildSelections(extractedData);

        // PROBLEM section - radios and checkboxes
        fillProblemSection(form, selections);

        // DATA section
        fillDataSection(form, selections);

        // RISK section
        fillRiskSection(form, selections);
    }

    private Map<String, Object> buildSelections(Map<String, Object> data) {
        Map<String, Object> out = new HashMap<>();
        List<Map<String, String>> copa = excelReader.getCopaData();
        List<Map<String, String>> dataSheet = excelReader.getDataSheet();
        List<Map<String, String>> risk = excelReader.getRiskData();

        // Derive from output.json + Excel lookup
        List<String> diagnoses = getDiagnoses(data);
        List<String> imaging = getImaging(data);

        // Apply Excel-based overrides when diagnosis matches copa/risk sheet
        applyExcelOverrides(out, diagnoses, copa, dataSheet, risk);

        // Problem: match diagnoses to copa sheet; default to acute_uncomplicated for fractures
        out.put("problem.self_limited_minor_problems", "0");
        out.put("problem.stable_chronic", "0");
        out.put("problem.stable_acute", false);
        out.put("problem.acute_uncomplicated", true);   // toe fracture
        out.put("problem.chronic_exac_progr_side_effects", false);
        out.put("problem.acute_complicated", false);
        out.put("problem.acute_systemic_symptoms", false);
        out.put("problem.undiag_prob_uncertain_prog", false);
        out.put("problem.chronic_severe_exab_progr_se", false);
        out.put("problem.acute_or_chronic_threat_to_life_and_body", false);

        // Data: from imaging, labs, external review
        out.put("data.data_not_applicable", false);
        out.put("data.minimal_or_none", false);
        out.put("data.review_of_external_notes", hasExternalReview(data) ? "2" : "0");
        boolean hasXray = imaging.stream().anyMatch(i -> i.toLowerCase().contains("xr") || i.toLowerCase().contains("x-ray"));
        out.put("data.order_review_of_test_results_xray", hasXray ? "1" : "0");
        boolean hasLabReview = hasLabOrdersOrReview(data);
        out.put("data.order_review_of_test_results_other", hasLabReview ? "3" : "0");  // Labs
        out.put("data.order_review_of_test_results_ekg", "0");
        out.put("data.order_review_of_test_results_poc_us", "0");
        out.put("data.indep_interpret_of_test_by_another_xray", hasIndepXrayInterpretation(data) ? "2" : "0");
        out.put("data.discuss_mgnt_or_test_interpret_with_another", hasExternalDiscussion(data));

        // Risk: OTC meds, rad exposure extremity x-ray, rigid immobilization
        out.put("risk.minimal", false);
        out.put("risk.otc_medications", true);   // Tylenol, ibuprofen
        out.put("risk.rad_exposure_extremity_xr", true);  // foot x-ray
        out.put("risk.rigid_musculoskeletal_immobilization", true);  // buddy tape/splint

        return out;
    }

    /** When Excel has matching rows for diagnosis, override selections. */
    private void applyExcelOverrides(Map<String, Object> out, List<String> diagnoses,
            List<Map<String, String>> copa, List<Map<String, String>> dataSheet, List<Map<String, String>> risk) {
        for (String dx : diagnoses) {
            if (dx == null || dx.isBlank()) continue;
            String dxLower = dx.toLowerCase();
            for (Map<String, String> row : copa) {
                for (String val : row.values()) {
                    if (val != null && val.length() > 3 && dxLower.contains(val.toLowerCase()))
                        applyCopaRow(out, row);
                }
            }
            for (Map<String, String> row : risk) {
                for (String val : row.values()) {
                    if (val != null && val.length() > 3 && dxLower.contains(val.toLowerCase()))
                        applyRiskRow(out, row);
                }
            }
        }
    }

    private void applyCopaRow(Map<String, Object> out, Map<String, String> row) {
        for (Map.Entry<String, String> e : row.entrySet()) {
            String k = e.getKey().toLowerCase();
            String v = e.getValue();
            if (v == null || v.isBlank()) continue;
            if (k.contains("self_limited") || k.contains("minor")) out.put("problem.self_limited_minor_problems", v);
            if (k.contains("stable_chronic")) out.put("problem.stable_chronic", v);
            if (k.contains("acute_uncomplicated")) out.put("problem.acute_uncomplicated", "1".equals(v) || "true".equalsIgnoreCase(v));
            if (k.contains("acute_complicated")) out.put("problem.acute_complicated", "1".equals(v) || "true".equalsIgnoreCase(v));
        }
    }

    private void applyRiskRow(Map<String, Object> out, Map<String, String> row) {	
        for (Map.Entry<String, String> e : row.entrySet()) {
            String k = e.getKey().toLowerCase();
            String v = e.getValue();
            if (v == null || v.isBlank()) continue;
            if (k.contains("otc") || k.contains("minimal")) out.put("risk.otc_medications", "1".equals(v) || "true".equalsIgnoreCase(v));
            if (k.contains("rad_exposure") || k.contains("extremity")) out.put("risk.rad_exposure_extremity_xr", "1".equals(v) || "true".equalsIgnoreCase(v));
            if (k.contains("rigid") || k.contains("immobilization")) out.put("risk.rigid_musculoskeletal_immobilization", "1".equals(v) || "true".equalsIgnoreCase(v));
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getDiagnoses(Map<String, Object> data) {
        List<String> out = new ArrayList<>();
        Object diag = data.get("diagnoses");
        if (diag instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object d = m.get("description");
                    if (d != null) out.add(d.toString());
                }
            }
        }
        Object edDiag = data.get("ed_diagnosis");
        if (edDiag instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object d = m.get("diagnosis");
                    if (d != null) out.add(d.toString());
                }
            }
        }
        return out;
    }

    private List<String> getImaging(Map<String, Object> data) {
        List<String> out = new ArrayList<>();
        Object img = data.get("imaging");
        if (img != null) out.add(img.toString());
        Object orders = data.get("ed_imaging_orders");
        if (orders instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object ord = m.get("order");
                    if (ord != null) out.add(ord.toString());
                }
            }
        }
        return out;
    }


    private boolean hasExternalReview(Map<String, Object> data) {
        Object ext = data.get("external_records_reviewed");
        return ext != null && !ext.toString().isBlank() && !ext.toString().equalsIgnoreCase("N/A");
    }

    private boolean hasExternalDiscussion(Map<String, Object> data) {
        Object mdm = data.get("medical_decision_making");
        if (mdm != null) {
            String s = mdm.toString().toLowerCase();
            if (s.contains("discuss") && (s.contains("external") || s.contains("another provider") || s.contains("specialist")))
                return true;
        }
        return false;
    }

    private boolean hasLabOrdersOrReview(Map<String, Object> data) {
        Object results = data.get("results");
        if (results != null && !results.toString().isBlank() && !results.toString().equalsIgnoreCase("None"))
            return true;
        Object orders = data.get("ed_all_orders");
        if (orders instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object ord = m.get("ordered");
                    if (ord != null && (ord.toString().toLowerCase().contains("lab") || ord.toString().toLowerCase().contains("cbc") || ord.toString().toLowerCase().contains("bmp")))
                        return true;
                }
            }
        }
        return false;
    }

    private boolean hasIndepXrayInterpretation(Map<String, Object> data) {
        Object mdm = data.get("medical_decision_making");
        return mdm != null && mdm.toString().toLowerCase().contains("independently interpreted") && mdm.toString().toLowerCase().contains("imaging");
    }

    private void fillProblemSection(Locator form, Map<String, Object> selections) {
        for (String key : List.of("problem.self_limited_minor_problems", "problem.stable_chronic")) {
            Object v = selections.get(key);
            if (v == null) continue;
            String val = v.toString();
            Locator radio = form.locator("input[name='" + key + "'][value='" + val + "']");
            if (radio.count() > 0) {
                ps.click(radio.first(), key + "=" + val);
                sleep(100);
            }
        }
        for (String key : List.of("problem.stable_acute", "problem.acute_uncomplicated", "problem.chronic_exac_progr_side_effects",
                "problem.acute_complicated", "problem.acute_systemic_symptoms", "problem.undiag_prob_uncertain_prog",
                "problem.chronic_severe_exab_progr_se", "problem.acute_or_chronic_threat_to_life_and_body")) {
            Object v = selections.get(key);
            if (!Boolean.TRUE.equals(v)) continue;
            Locator cb = form.locator("input#jsonform-2-elt-" + key.replace(".", "---") + ", input[name='" + key + "']");
            if (cb.count() > 0 && !cb.first().isChecked()) {
                ps.click(cb.first(), key);
                sleep(100);
            }
        }
    }

    private void fillDataSection(Locator form, Map<String, Object> selections) {
        for (String key : List.of("data.review_of_external_notes", "data.order_review_of_test_results_ekg",
                "data.order_review_of_test_results_xray", "data.order_review_of_test_results_poc_us",
                "data.order_review_of_test_results_other", "data.indep_interpret_of_test_by_another_ekg",
                "data.indep_interpret_of_test_by_another_xray", "data.indep_interpret_of_test_by_another_poc_us")) {
            Object v = selections.get(key);
            if (v == null) continue;
            String val = v.toString();
            Locator radio = form.locator("input[name='" + key + "'][value='" + val + "']");
            if (radio.count() > 0) {
                ps.click(radio.first(), key + "=" + val);
                sleep(100);
            }
        }
        for (String key : List.of("data.data_not_applicable", "data.minimal_or_none", "data.discuss_mgnt_or_test_interpret_with_another")) {
            Object v = selections.get(key);
            if (!Boolean.TRUE.equals(v)) continue;
            String id = "jsonform-2-elt-" + key.replace(".", "---");
            Locator cb = form.locator("input#" + id + ", input[name='" + key + "']");
            if (cb.count() > 0 && !cb.first().isChecked()) {
                ps.click(cb.first(), key);
                sleep(100);
            }
        }
    }

    private void fillRiskSection(Locator form, Map<String, Object> selections) {
        for (String key : List.of("risk.minimal", "risk.otc_medications", "risk.rad_exposure_extremity_xr",
                "risk.rigid_musculoskeletal_immobilization", "risk.prescription_drugs", "risk.iv_fluids_with_or_without_additives")) {
            Object v = selections.get(key);
            if (!Boolean.TRUE.equals(v)) continue;
            String id = "jsonform-2-elt-" + key.replace(".", "---");
            Locator cb = form.locator("input#" + id + ", input[name='" + key + "']");
            if (cb.count() > 0 && !cb.first().isChecked()) {
                ps.click(cb.first(), key);
                sleep(100);
            }
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
