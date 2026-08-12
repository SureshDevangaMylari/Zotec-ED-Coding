package com.wl.zotecAgent.selection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wl.zotecAgent.edem.ED_EMExcelReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wl.ai.LLMService;

import java.io.IOException;
import java.util.*;

/**
 * Automated ED EM Supplemental form selection via Qwen LLM.
 * <p>
 * Approach: Program reads output.json + Excel file, passes both to Qwen LLM.
 * LLM analyzes the data and returns the best selection result. No manual reading or analysis.
 * <p>
 * Flow: output.json + Excel -> LLM (Qwen) -> analyze -> JSON selections -> HTML update
 */
public class LLMSelectionService {

    private static final Logger log = LoggerFactory.getLogger(LLMSelectionService.class);
    /** Max chars for output.json in prompt (model context ~30k tokens; ~4 chars/token -> ~60k total; reserve for system+excel+output) */
    private static final int MAX_OUTPUT_JSON_CHARS = 35_000;
    /** Max items per array in filtered output (medical_history, ed_all_orders, etc.) */
    private static final int MAX_ARRAY_ITEMS = 40;

    private final LLMService llmService;
    private final ED_EMExcelReader excelReader;
    private final ObjectMapper mapper = new ObjectMapper();

    public LLMSelectionService() {
        this(new LLMService(), new ED_EMExcelReader());
    }

    public LLMSelectionService(LLMService llmService, ED_EMExcelReader excelReader) {
        this.llmService = llmService != null ? llmService : new LLMService();
        this.excelReader = excelReader != null ? excelReader : new ED_EMExcelReader();
    }

    /**
     * Decides form selections: output.json + Excel reference -> if criteria matches -> select.
     * Not section-wise - each selection is driven by output.json data matching Excel criterion.
     *
     * @param outputJson patient/encounter data from output.json
     * @return Map of field name -> value ("0"/"1"/"2"/"3" for radios, Boolean for checkboxes)
     */
    public Map<String, Object> decideSelections(Map<String, Object> outputJson) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(outputJson);

        try {
            log.info("Calling LLM for ED EM form selection...");
            Map<String, Object> raw = llmService.callToMap(systemPrompt, userPrompt, 1024, 0.1);
            Map<String, Object> normalized = normalizeSelections(raw);
            return enforceSingleProblemSelection(normalized);
        } catch (IOException e) {
            log.warn("LLM call failed: {}, falling back to empty selections", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a professional medical coder. You receive output.json and Excel reference.\n\n");
        sb.append("## Selection logic (NOT section-wise)\n");
        sb.append("Do NOT process by section. Instead: for each data point in output.json, check if it matches ");
        sb.append("any criteria in the Excel reference. If it matches criteria -> select/click that form field.\n\n");
        sb.append("Rule: output.json data + Excel reference -> if criteria matches -> choose that option.\n\n");
        sb.append("## Flow\n");
        sb.append("1. Take output.json data (diagnoses, orders, labs, imaging, meds, procedures, etc.)\n");
        sb.append("2. Reference with Excel (COPA, DATA, RISK, Major or Minor Procedures, Drugs sheets)\n");
        sb.append("3. For each output.json item: does it match Excel criterion? Yes -> add that field to selections\n");
        sb.append("4. Return JSON of all fields where criteria matched. No section-based logic.\n\n");

        sb.append("## Form field names (when criteria matches, select these)\n");
        sb.append("PROBLEM radios: problem.self_limited_minor_problems, problem.stable_chronic (values 0/1/2)\n");
        sb.append("PROBLEM checkboxes (pick at most one before dashes, one after): problem.stable_acute, problem.acute_uncomplicated, ");
        sb.append("problem.chronic_exac_progr_side_effects, problem.acute_complicated, problem.acute_systemic_symptoms, ");
        sb.append("problem.undiag_prob_uncertain_prog, problem.chronic_severe_exab_progr_se, problem.acute_or_chronic_threat_to_life_and_body\n");
        sb.append("DATA: data.review_of_external_notes, data.order_review_of_test_results_ekg, data.order_review_of_test_results_xray, ");
        sb.append("data.order_review_of_test_results_poc_us, data.order_review_of_test_results_other, ");
        sb.append("data.indep_interpret_of_test_by_another_ekg, data.indep_interpret_of_test_by_another_xray, data.indep_interpret_of_test_by_another_poc_us (radios 0-3 or checkbox)\n");
        sb.append("RISK: risk.minimal, risk.rest_gargle_bandage_dressing, risk.otc_medications, risk.prescription_drugs, ");
        sb.append("risk.iv_fluids_with_or_without_additives, risk.rad_exposure_extremity_xr, risk.rad_exposure_ct_or_xr_head_neck_torso, ");
        sb.append("risk.rigid_musculoskeletal_immobilization, risk.ct_scan_with_iv_contrast (checkboxes: true). Use Excel RISK sheet for more.\n\n");

        sb.append("## Response: pure JSON. Only include fields where output.json matched Excel criteria. E.g.\n");
        sb.append("{\"problem.self_limited_minor_problems\": \"0\", \"problem.stable_chronic\": \"1\", ");
        sb.append("\"problem.acute_uncomplicated\": true, \"data.order_review_of_test_results_ekg\": \"1\", ");
        sb.append("\"data.order_review_of_test_results_other\": \"2\", \"risk.iv_fluids_with_or_without_additives\": true}\n");
        sb.append("PROBLEM: max one checkbox before dashes, max one after. DATA/RISK: every field where criteria matched.");

        return sb.toString();
    }

    private String buildUserPrompt(Map<String, Object> outputJson) {
        StringBuilder sb = new StringBuilder();

        sb.append("## output.json (encounter data - filtered for ED EM relevance)\n\n");
        sb.append(filteredOutputJsonForEdEm(outputJson));

        sb.append("\n\n## Excel reference (all sheets)\n\n");
        int maxExcelRowsPerSheet = 50;
        for (String sheetName : excelReader.getSheetNames()) {
            List<Map<String, String>> rows = excelReader.getSheet(sheetName);
            if (rows.isEmpty()) continue;
            int toShow = Math.min(rows.size(), maxExcelRowsPerSheet);
            sb.append("### ").append(sheetName).append(" (").append(toShow);
            if (rows.size() > maxExcelRowsPerSheet)
                sb.append(" of ").append(rows.size());
            sb.append(" rows)\n");
            for (int i = 0; i < toShow; i++) {
                sb.append("- ").append(rows.get(i)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Task\n");
        sb.append("Reference output.json with Excel. For each output.json item: if it matches any Excel criterion, select that form field. ");
        sb.append("Do not select by section - select only when criteria matches. Return JSON of matched selections.");

        return sb.toString();
    }

    /** Keys relevant for ED EM form (COPA, DATA, RISK) - omit verbose/irrelevant sections. */
    private static final Set<String> ED_EM_RELEVANT_KEYS = Set.of(
            "medical_history", "ed_all_orders", "billing", "chief_complaint", "hpi", "secondary_diagnoses",
            "ed_medication_orders", "ed_micro_lab_poct", "home_medications", "patient", "batch_info",
            "ekg", "discharge_references_attachments", "review_of_systems", "mode_of_arrival", "source",
            "code_iso_restraint", "ed_course", "notes");

    /** Filter output.json to ED EM-relevant fields and truncate long arrays to stay within token limit. */
    @SuppressWarnings("unchecked")
    private String filteredOutputJsonForEdEm(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return "{}";
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String key : ED_EM_RELEVANT_KEYS) {
            Object v = data.get(key);
            if (v == null) continue;
            if (v instanceof List<?> list) {
                List<?> truncated = list.size() <= MAX_ARRAY_ITEMS ? list : list.subList(0, MAX_ARRAY_ITEMS);
                filtered.put(key, truncated);
            } else if (v instanceof Map<?, ?> m) {
                filtered.put(key, new LinkedHashMap<>((Map<String, Object>) m));
            } else {
                filtered.put(key, v);
            }
        }
        String json;
        try {
            json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(filtered);
        } catch (JsonProcessingException ex) {
            json = filtered.toString();
        }
        if (json.length() > MAX_OUTPUT_JSON_CHARS) {
            json = json.substring(0, MAX_OUTPUT_JSON_CHARS) + "\n... [truncated]";
        }
        log.debug("Filtered output.json size: {} chars (max {})", json.length(), MAX_OUTPUT_JSON_CHARS);
        return json;
    }

    /** Before dashes: stable_acute, acute_uncomplicated. After dashes: rest. */
    private static final String[] CHECKBOX_BEFORE_DASHES = { "problem.stable_acute", "problem.acute_uncomplicated" };
    private static final String[] CHECKBOX_AFTER_DASHES = {
        "problem.acute_or_chronic_threat_to_life_and_body",
        "problem.chronic_severe_exab_progr_se",
        "problem.acute_systemic_symptoms",
        "problem.undiag_prob_uncertain_prog",
        "problem.chronic_exac_progr_side_effects",
        "problem.acute_complicated"
    };

    private Map<String, Object> enforceSingleProblemSelection(Map<String, Object> selections) {
        Map<String, Object> out = new LinkedHashMap<>(selections);
        // Ensure both radios present (default "0")
        out.putIfAbsent("problem.self_limited_minor_problems", "0");
        out.putIfAbsent("problem.stable_chronic", "0");
        // Before dashes: keep at most one (prefer acute_uncomplicated over stable_acute)
        String keepBefore = null;
        for (String k : CHECKBOX_BEFORE_DASHES) {
            if (Boolean.TRUE.equals(out.get(k))) { keepBefore = k; break; }
        }
        if (keepBefore != null) {
            for (String k : CHECKBOX_BEFORE_DASHES) {
                if (!k.equals(keepBefore)) out.remove(k);
            }
        }
        // After dashes: keep at most one (highest priority)
        List<String> after = new ArrayList<>();
        for (String k : CHECKBOX_AFTER_DASHES) {
            if (Boolean.TRUE.equals(out.get(k))) after.add(k);
        }
        if (after.size() > 1) {
            String keepAfter = null;
            for (String k : CHECKBOX_AFTER_DASHES) {
                if (after.contains(k)) { keepAfter = k; break; }
            }
            for (String k : after) {
                if (!k.equals(keepAfter)) out.remove(k);
            }
        }
        return out;
    }

    /** Normalize LLM response to expected format: String "0"/"1"/"2"/"3" or Boolean. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeSelections(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();

        // Handle raw text wrapper (parse failed)
        Object rawText = raw.get("raw");
        if (rawText != null) {
            try {
                Map<String, Object> parsed = llmService.parseToMap(rawText.toString());
                return normalizeSelections(parsed);
            } catch (Exception ignored) {
                return out;
            }
        }

        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if (key == null || !key.startsWith("problem.") && !key.startsWith("data.") && !key.startsWith("risk."))
                continue;
            if (val == null) continue;

            if (val instanceof Boolean b)
                out.put(key, b);
            else if (val instanceof Number n) {
                int v = n.intValue();
                if (v >= 0 && v <= 3)
                    out.put(key, String.valueOf(v));
            } else {
                String s = val.toString().trim();
                if (s.equalsIgnoreCase("true"))
                    out.put(key, Boolean.TRUE);
                else if (s.equalsIgnoreCase("false"))
                    out.put(key, Boolean.FALSE);
                else if (s.matches("[0-3]"))
                    out.put(key, s);
            }
        }
        return out;
    }
}
