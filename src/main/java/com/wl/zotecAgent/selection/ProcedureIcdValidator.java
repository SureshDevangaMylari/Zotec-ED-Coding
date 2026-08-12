package com.wl.zotecAgent.selection;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.wl.util.JsonReadService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Validates procedure codes (CPT/HCPCS) and ICD/diagnosis codes from the charges
 * table against output.json. Prints a message when a code is not found in output.json.
 */
public class ProcedureIcdValidator {

    private static final Logger log = LoggerFactory.getLogger(ProcedureIcdValidator.class);

    /**
     * Validates procedure and ICD codes against output.json.
     * Uses JsonReadService to read output.json, then compares:
     * - Procedure codes vs ed_charges[].code, procedures_detail[].code/cpt_code
     * - ICD codes vs billing.icd_codes, diagnoses[].code, final_diagnoses.primary[].code
     * <p>
     * If a code exists in output.json, nothing is done. If not found, prints:
     * "XXX code not matched with the output.json of procedure code" or
     * "XXX code not matched with the output.json of diagnosis code"
     *
     * @param procedureCodes list of procedure codes from the charges table (e.g. 99284, 93010, G8952)
     * @param diagnosisCodes  list of ICD/diagnosis codes from the charges table (e.g. W01.0XXA, S06.0XXA)
     */
    public static void validateProceduresIcd(List<String> procedureCodes, List<String> diagnosisCodes) {
        JsonReadService reader = new JsonReadService();
        Map<String, Object> data = reader.readOutputJson();

        Set<String> jsonProcedureCodes = collectProcedureCodesFromJson(data);
        Set<String> jsonDiagnosisCodes = collectDiagnosisCodesFromJson(data);

        if (procedureCodes != null) {
            for (String code : procedureCodes) {
                String trimmed = code != null ? code.trim() : "";
                if (trimmed.isEmpty())
                    continue;
                if (!jsonProcedureCodes.contains(trimmed) && !containsIgnoreCase(jsonProcedureCodes, trimmed)) {
                    System.out.println(trimmed + " code not matched with the output.json of procedure code");
                }
            }
        }

        if (diagnosisCodes != null) {
            for (String code : diagnosisCodes) {
                String trimmed = code != null ? code.trim() : "";
                if (trimmed.isEmpty())
                    continue;
                // Handle comma-separated diagnosis pointers or multiple codes
                for (String single : trimmed.split("[,;]")) {
                    String s = single.trim();
                    if (s.isEmpty())
                        continue;
                    if (!jsonDiagnosisCodes.contains(s) && !containsIgnoreCase(jsonDiagnosisCodes, s)) {
                        System.out.println(s + " code not matched with the output.json of diagnosis code");
                    }
                }
            }
        }
    }

    /**
     * Overload: reads output.json from a custom path.
     */
    public static void validateProceduresIcd(List<String> procedureCodes, List<String> diagnosisCodes, String outputJsonPath) {
        JsonReadService reader = new JsonReadService();
        Map<String, Object> data = reader.readFromPath(outputJsonPath);

        Set<String> jsonProcedureCodes = collectProcedureCodesFromJson(data);
        Set<String> jsonDiagnosisCodes = collectDiagnosisCodesFromJson(data);

        if (procedureCodes != null) {
            for (String code : procedureCodes) {
                String trimmed = code != null ? code.trim() : "";
                if (trimmed.isEmpty())
                    continue;
                if (!jsonProcedureCodes.contains(trimmed) && !containsIgnoreCase(jsonProcedureCodes, trimmed)) {
                    System.out.println(trimmed + " code not matched with the output.json of procedure code");
                }
            }
        }

        if (diagnosisCodes != null) {
            for (String code : diagnosisCodes) {
                String trimmed = code != null ? code.trim() : "";
                if (trimmed.isEmpty())
                    continue;
                for (String single : trimmed.split("[,;]")) {
                    String s = single.trim();
                    if (s.isEmpty())
                        continue;
                    if (!jsonDiagnosisCodes.contains(s) && !containsIgnoreCase(jsonDiagnosisCodes, s)) {
                        System.out.println(s + " code not matched with the output.json of diagnosis code");
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> collectProcedureCodesFromJson(Map<String, Object> data) {
        Set<String> codes = new HashSet<>();

        // ed_charges[].code
        Object edCharges = data.get("ed_charges");
        if (edCharges instanceof List) {
            for (Object item : (List<?>) edCharges) {
                if (item instanceof Map) {
                    String code = safeStr((Map<String, Object>) item, "code");
                    if (code != null) codes.add(code);
                    String cpt = safeStr((Map<String, Object>) item, "cpt_code");
                    if (cpt != null) codes.add(cpt);
                }
            }
        }

        // procedures_detail[].code or cpt_code
        Object procDetail = data.get("procedures_detail");
        if (procDetail instanceof List) {
            for (Object item : (List<?>) procDetail) {
                if (item instanceof Map) {
                    String code = safeStr((Map<String, Object>) item, "code");
                    if (code != null) codes.add(code);
                    String cpt = safeStr((Map<String, Object>) item, "cpt_code");
                    if (cpt != null) codes.add(cpt);
                }
            }
        }

        return codes;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> collectDiagnosisCodesFromJson(Map<String, Object> data) {
        Set<String> codes = new HashSet<>();

        // billing.icd_codes
        Object billing = data.get("billing");
        if (billing instanceof Map) {
            Object icdList = ((Map<String, Object>) billing).get("icd_codes");
            if (icdList instanceof List) {
                for (Object o : (List<?>) icdList) {
                    String s = asCode(o);
                    if (s != null) codes.add(s);
                }
            }
        }

        // diagnoses[].code
        Object diagnoses = data.get("diagnoses");
        if (diagnoses instanceof List) {
            for (Object item : (List<?>) diagnoses) {
                if (item instanceof Map) {
                    String code = safeStr((Map<String, Object>) item, "code");
                    if (code != null) codes.add(code);
                    String icd = safeStr((Map<String, Object>) item, "icd_code");
                    if (icd != null) codes.add(icd);
                }
            }
        }

        // final_diagnoses.primary[].code
        Object finalDiag = data.get("final_diagnoses");
        if (finalDiag instanceof Map) {
            Object primary = ((Map<String, Object>) finalDiag).get("primary");
            if (primary instanceof List) {
                for (Object item : (List<?>) primary) {
                    if (item instanceof Map) {
                        String code = safeStr((Map<String, Object>) item, "code");
                        if (code != null) codes.add(code);
                    }
                }
            }
        }

        return codes;
    }

    private static String safeStr(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        return asCode(v);
    }

    private static String asCode(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        if (set == null || value == null) return false;
        return set.stream().anyMatch(s -> s != null && s.equalsIgnoreCase(value));
    }

    /**
     * Extracts procedure and diagnosis codes from the filled form HTML, then validates
     * them against output.json. Looks for input[name="procedureCode"] and
     * input[name="diagnosisCodes"] or id^="diagnosis".
     *
     * @param htmlPath path to autoCoderForm*.html (e.g. resources/output/autoCoderForm_Myers_William_Don.html)
     */
    public static void validateProceduresIcdFromHtml(String htmlPath) {
        Path p = Path.of(htmlPath);
        if (!Files.exists(p))
            p = Path.of(System.getProperty("user.dir"), htmlPath);
        if (!Files.exists(p)) {
            log.warn("HTML file not found for validation: {}", htmlPath);
            return;
        }

        try {
            validateProceduresIcdFromHtmlFragment(Files.readString(p));
        } catch (IOException e) {
            log.warn("Failed to read HTML for validation: {}", e.getMessage());
        }
    }

    /**
     * Same as {@link #validateProceduresIcdFromHtml(String)} but parses a live DOM fragment from Playwright
     * ({@code #autoCoderForm} inside the supplemental modal when present).
     */
    public static void validateProceduresIcdFromPage(Page page) {
        if (page == null) {
            return;
        }
        Locator form = page.locator("#codingAssistantBody #autoCoderForm");
        if (form.count() == 0) {
            form = page.locator("#autoCoderForm");
        }
        if (form.count() == 0) {
            log.warn("No #autoCoderForm on page for procedure/ICD validation");
            return;
        }
        Object outer = form.first().evaluate("el => el.outerHTML");
        String html = outer != null ? outer.toString() : "";
        validateProceduresIcdFromHtmlFragment(html);
    }

    /**
     * Parses an HTML string (file contents or {@code outerHTML} of {@code #autoCoderForm}) and validates codes.
     */
    public static void validateProceduresIcdFromHtmlFragment(String html) {
        if (html == null || html.isBlank()) {
            return;
        }
        List<String> procedureCodes = new ArrayList<>();
        List<String> diagnosisCodes = new ArrayList<>();

        Document doc = Jsoup.parse(html);

        Elements procInputs = doc.select("input[name=procedureCode]");
        procInputs.forEach(el -> {
            String v = el.attr("value");
            if (v != null && !v.isBlank()) procedureCodes.add(v.trim());
        });

        Elements diagInputs = doc.select("input[name=diagnosisCodes], input[id^=diagnosis]");
        diagInputs.forEach(el -> {
            String v = el.attr("value");
            if (v != null && !v.isBlank()) diagnosisCodes.add(v.trim());
        });

        validateProceduresIcd(procedureCodes, diagnosisCodes);
    }
}
