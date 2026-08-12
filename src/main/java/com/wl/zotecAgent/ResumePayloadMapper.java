package com.wl.zotecAgent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.wl.zotecAgent.selection.FormFieldConstants;

/**
 * Maps the document-processor resume JSON ({@code patient}, {@code cpt}, {@code icd}, {@code ed})
 * into shapes used by {@link CodingFormValidationService}, {@link ED_EMFormPlaywrightApplier},
 * and {@link Service#validateCPT} / {@link Service#validateICD}.
 * <p>
 * Accepts both a flat resume payload and a review API envelope that nests the
 * payload under {@code reviewed_result}.
 */
public final class ResumePayloadMapper {

    private ResumePayloadMapper() {}

    /** Keys under {@code ed.data} that hold nested order/interpret counts. */
    private static final String ORDER_TESTS = "Order and/or Review of Tests";
    private static final String INTERPRET_TESTS = "Independent Interpretation of Tests";

    /**
     * Builds the map expected by {@link CodingFormValidationService#validateAndUpdate(Map)}.
     * Supports flat payloads and review responses wrapping data in {@code reviewed_result}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toValidationMap(Map<String, Object> resumePayload) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> root = resolveResumeRoot(resumePayload);
        if (root.isEmpty()) {
            return out;
        }

        copyIfMap(out, root, "identifiers", "batch_info", "encounter", "coding",
                "ed_disposition", "disposition", "providers");

        Map<String, Object> patient = asMap(root.get("patient"));
        if (patient != null) {
            Map<String, Object> normalizedPatient = mergeMap(asMap(out.get("patient")), patient);
            putIfPresent(normalizedPatient, "name", firstStr(patient, "patient_name", "name"));
            putIfPresent(normalizedPatient, "date_of_birth", str(patient, "date_of_birth"));
            putIfPresent(normalizedPatient, "gender", str(patient, "patient_gender", "gender"));
            putIfPresent(normalizedPatient, "chief_complaint", str(patient, "chief_complaint"));
            putIfPresent(normalizedPatient, "rendering_provider", str(patient, "rendering_provider"));
            putIfPresent(normalizedPatient, "supervising_provider", str(patient, "supervising_provider"));
            putIfPresent(normalizedPatient, "mrn", firstStr(patient, "mrn", "medical_record_number"));
            putIfPresent(normalizedPatient, "profile_code",
                    firstStr(patient, "profile_code", "profile", "profileId"));
            out.put("patient", normalizedPatient);

            Map<String, Object> identifiers = mergeMap(asMap(out.get("identifiers")), new LinkedHashMap<>());
            putIfPresent(identifiers, "mrn", firstStr(patient, "mrn", "medical_record_number"));
            putIfPresent(identifiers, "account_number",
                    firstStr(patient, "account_number", "encounter_account_number", "encounter_number"));
            putIfPresent(identifiers, "encounter_number", str(patient, "encounter_number"));
            putIfPresent(identifiers, "patient_id", firstStr(patient, "patient_id", "mrn"));
            if (!identifiers.isEmpty()) {
                out.put("identifiers", identifiers);
            }

            Map<String, Object> encounter = mergeMap(asMap(out.get("encounter")), new LinkedHashMap<>());
            putIfPresent(encounter, "date_of_service", str(patient, "date_of_service"));
            putIfPresent(encounter, "service", str(patient, "service", "encounter_location", "department"));
            putIfPresent(encounter, "status", str(patient, "encounter_status", "status", "disposition"));
            if (!encounter.isEmpty()) {
                out.put("encounter", encounter);
            }

            Map<String, Object> providers = buildProviders(patient);
            if (!providers.isEmpty()) {
                out.put("providers", providers);
            }

            String serviceLocation = firstStr(patient, "service_location", "facility_name", "batch_text");
            if (serviceLocation == null) {
                serviceLocation = firstCptServiceLocation(root);
            }
            if (serviceLocation != null) {
                Map<String, Object> batchInfo = mergeMap(asMap(out.get("batch_info")), new LinkedHashMap<>());
                putIfPresent(batchInfo, "batch_text", serviceLocation);
                out.put("batch_info", batchInfo);
                Map<String, Object> coding = mergeMap(asMap(out.get("coding")), new LinkedHashMap<>());
                putIfPresent(coding, "service_location", serviceLocation);
                out.put("coding", coding);
            }

            // POS: patient.pos_code, else first CPT pos (e.g. "23")
            String pos = firstStr(patient, "pos_code", "pos", "place_of_service");
            if (pos == null) {
                pos = firstCptPos(root);
            }
            if (pos != null) {
                Map<String, Object> coding = mergeMap(asMap(out.get("coding")), new LinkedHashMap<>());
                putIfPresent(coding, "place_of_service", pos);
                putIfPresent(coding, "pos", pos);
                out.put("coding", coding);
                putIfPresent(normalizedPatient, "pos_code", pos);
                out.put("patient", normalizedPatient);
            }
        }

        List<Object> diagnoses = buildDiagnoses(root);
        if (!diagnoses.isEmpty()) {
            out.put("diagnoses", diagnoses);
        }

        // Ensure POS is available even when patient block was absent
        Map<String, Object> codingForPos = asMap(out.get("coding"));
        if (codingForPos == null || firstStr(codingForPos, "pos", "place_of_service") == null) {
            String pos = firstCptPos(root);
            if (pos != null) {
                Map<String, Object> coding = mergeMap(codingForPos, new LinkedHashMap<>());
                putIfPresent(coding, "place_of_service", pos);
                putIfPresent(coding, "pos", pos);
                out.put("coding", coding);
            }
        }

        // Department for CodingFormValidationService.updateDepartment
        String department = firstStr(patient != null ? patient : Map.of(), "department");
        if (department == null) {
            Map<String, Object> enc = asMap(out.get("encounter"));
            department = firstStr(enc, "service", "department");
        }
        if (department == null) {
            Map<String, Object> coding = asMap(root.get("coding"));
            department = firstStr(coding, "department");
        }
        if (department != null) {
            Map<String, Object> coding = mergeMap(asMap(out.get("coding")), new LinkedHashMap<>());
            putIfPresent(coding, "department", department);
            out.put("coding", coding);
            if (patient != null) {
                Map<String, Object> p = mergeMap(asMap(out.get("patient")), new LinkedHashMap<>());
                putIfPresent(p, "department", department);
                out.put("patient", p);
            }
        }

        // Disposition labels for header multi-select
        List<String> dispositionLabels = extractDispositionLabels(root);
        if (!dispositionLabels.isEmpty()) {
            out.put("disposition_labels", dispositionLabels);
            Map<String, Object> coding = mergeMap(asMap(out.get("coding")), new LinkedHashMap<>());
            coding.put("disposition", new ArrayList<>(dispositionLabels));
            out.put("coding", coding);
        }

        // Top-level review fields used by CodingFormValidationService.updateBillingExtras
        putIfPresent(out, "billing_type", str(root, "billing_type"));
        putIfPresent(out, "accident_date", str(root, "accident_date"));
        putIfPresent(out, "accident_type", str(root, "accident_type"));
        if (root.containsKey("critical_care")) {
            out.put("critical_care", root.get("critical_care"));
        }

        Map<String, String> moveToIssue = extractMoveToIssue(resumePayload);
        if (moveToIssue != null && !moveToIssue.isEmpty()) {
            // Canonical internal key; source JSON may use "issue" or "move_to_issue"
            out.put("issue", new LinkedHashMap<>(moveToIssue));
            out.put("move_to_issue", new LinkedHashMap<>(moveToIssue));
        }

        Map<String, Object> rfi = extractRfi(resumePayload);
        if (rfi != null && !rfi.isEmpty()) {
            out.put("rfi", new LinkedHashMap<>(rfi));
        }

        out.put("source", "resume_payload");
        return out;
    }

    /**
     * Reads Move-to-Issue data from resume JSON under {@code issue} (preferred) or
     * legacy {@code move_to_issue} / {@code Move_to_Issue} / {@code moveToIssue}.
     * Returns {@code issue_type} and {@code comment}, or empty when missing / null / empty.
     * <p>
     * Supported shapes:
     * <pre>
     * "issue": { "issue_type": "Addendum Recieved", "issue_comment": "..." }
     * "move_to_issue": { "issue_type": "...", "comment": "..." }
     * </pre>
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> extractMoveToIssue(Map<String, Object> resumePayload) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Object> root = resolveResumeRoot(resumePayload);
        if (root.isEmpty()) {
            return out;
        }
        // Prefer "issue" (current LLM/review shape), keep legacy move_to_issue keys
        Object raw = getByAnyKey(root, "issue", "move_to_issue", "Move_to_Issue", "MoveToIssue",
                "moveToIssue");
        if (raw == null) {
            return out;
        }
        if (raw instanceof String s) {
            if (!s.isBlank() && !"null".equalsIgnoreCase(s.trim())) {
                out.put("issue_type", s.trim());
            }
            return out;
        }
        if (!(raw instanceof Map<?, ?>)) {
            return out;
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        if (m.isEmpty()) {
            return out;
        }
        String type = firstStr(m, "issue_type", "type", "issueType", "Issue Type", "issue_type_name");
        // Prefer issue_comment (current shape) then comment / aliases
        String comment = firstStr(m, "issue_comment", "comment", "issueComment", "Issue Comment",
                "comments");
        if (type != null && !type.isBlank() && !"null".equalsIgnoreCase(type.trim())) {
            out.put("issue_type", type.trim());
        }
        if (comment != null && !comment.isBlank() && !"null".equalsIgnoreCase(comment.trim())) {
            out.put("comment", comment.trim());
        }
        return out;
    }

    /** True when JSON has issue / move_to_issue with at least one of issue_type / comment. */
    public static boolean shouldApplyMoveToIssue(Map<String, Object> resumePayload) {
        Map<String, String> issue = extractMoveToIssue(resumePayload);
        return issue != null && (!isBlank(issue.get("issue_type")) || !isBlank(issue.get("comment")));
    }

    /**
     * Reads {@code rfi} / {@code RFI} from the resume JSON.
     * Returns optional {@code provider}, {@code procedure}, {@code reasons} (List),
     * and {@code comment}, or empty when missing / null / empty.
     * <p>
     * Supported shapes:
     * <pre>
     * "rfi": {
     *   "provider": " Edwin Doig",
     *   "procedure": "65220",
     *   "reasons": "Assistant Surgeon Role Not Documented",
     *   "comment": "..."
     * }
     * "rfi": { "reasons": ["..."], "procedure_code": "..." }
     * </pre>
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractRfi(Map<String, Object> resumePayload) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> root = resolveResumeRoot(resumePayload);
        if (root.isEmpty()) {
            return out;
        }
        Object raw = getByAnyKey(root, "RFI", "rfi", "Rfi");
        if (raw == null) {
            return out;
        }
        if (raw instanceof String s) {
            if (!s.isBlank() && !"null".equalsIgnoreCase(s.trim())) {
                out.put("comment", s.trim());
            }
            return out;
        }
        if (!(raw instanceof Map<?, ?>)) {
            return out;
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        if (m.isEmpty()) {
            return out;
        }

        String provider = firstStr(m, "provider", "rfi_provider", "rfiProvider", "RFI Provider",
                "RFI_Provider");
        String procedure = firstStr(m, "procedure", "procedure_code", "procedureCode", "Procedure",
                "cpt", "cpt_code");
        String comment = firstStr(m, "comment", "comments", "Comment", "rfi_comment", "rfiComment");
        List<String> reasons = extractRfiReasons(m);

        if (provider != null) {
            out.put("provider", provider.trim());
        }
        if (procedure != null) {
            out.put("procedure", procedure.trim());
        }
        if (!reasons.isEmpty()) {
            out.put("reasons", reasons);
        }
        if (comment != null) {
            out.put("comment", comment.trim());
        }
        return out;
    }

    /** True when JSON has RFI with at least one of provider / procedure / reasons / comment. */
    public static boolean shouldApplyRfi(Map<String, Object> resumePayload) {
        Map<String, Object> rfi = extractRfi(resumePayload);
        if (rfi == null || rfi.isEmpty()) {
            return false;
        }
        if (!isBlank(strObj(rfi.get("provider"))) || !isBlank(strObj(rfi.get("procedure")))
                || !isBlank(strObj(rfi.get("comment")))) {
            return true;
        }
        Object reasons = rfi.get("reasons");
        return reasons instanceof List<?> list && !list.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractRfiReasons(Map<String, Object> m) {
        List<String> out = new ArrayList<>();
        Object raw = firstPresent(m, "reasons", "reason", "reasonIds", "reason_ids", "Reasons",
                "RFI Reasons");
        if (raw == null) {
            return out;
        }
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
                return out;
            }
            // Current JSON sends one reason label as a plain string (e.g. "Assistant Surgeon Role Not Documented").
            // Split only on ; or | so commas inside a label are kept.
            if (trimmed.contains(";") || trimmed.contains("|")) {
                for (String part : trimmed.split("[;|]")) {
                    String t = part.trim();
                    if (!t.isEmpty() && !"null".equalsIgnoreCase(t)) {
                        out.add(t);
                    }
                }
            } else {
                out.add(trimmed);
            }
            return out;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                if (item instanceof Map<?, ?> rm) {
                    String label = firstStr((Map<String, Object>) rm, "name", "label", "text",
                            "reason", "description", "value");
                    if (label != null) {
                        out.add(label.trim());
                    }
                } else {
                    String t = String.valueOf(item).trim();
                    if (!t.isEmpty() && !"null".equalsIgnoreCase(t)) {
                        out.add(t);
                    }
                }
            }
        }
        return out;
    }

    private static Object getByAnyKey(Map<String, Object> root, String... keys) {
        if (root == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (root.containsKey(key)) {
                return root.get(key);
            }
        }
        for (String key : keys) {
            for (Map.Entry<String, Object> e : root.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static Object firstPresent(Map<String, Object> m, String... keys) {
        if (m == null) {
            return null;
        }
        for (String k : keys) {
            if (m.containsKey(k) && m.get(k) != null) {
                return m.get(k);
            }
        }
        for (String k : keys) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(k) && e.getValue() != null) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static String strObj(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Collects disposition search labels from ed_disposition, patient.disposition,
     * and critical_care DISPO_ESC codes.
     */
    @SuppressWarnings("unchecked")
    static List<String> extractDispositionLabels(Map<String, Object> root) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if (root == null) {
            return List.of();
        }

        Map<String, Object> edDisp = asMap(root.get("ed_disposition"));
        if (edDisp != null) {
            addDispositionLabel(labels, firstStr(edDisp, "status", "disposition", "name"));
        }
        Object topDisp = root.get("disposition");
        if (topDisp instanceof String s) {
            addDispositionLabel(labels, s);
        } else if (topDisp instanceof Map<?, ?> m) {
            addDispositionLabel(labels, firstStr((Map<String, Object>) m, "status", "disposition", "name"));
        } else if (topDisp instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String) {
                    addDispositionLabel(labels, (String) item);
                } else if (item instanceof Map<?, ?> im) {
                    addDispositionLabel(labels,
                            firstStr((Map<String, Object>) im, "status", "disposition", "name", "text"));
                }
            }
        }

        Map<String, Object> patient = asMap(root.get("patient"));
        if (patient != null) {
            addDispositionLabel(labels, firstStr(patient, "disposition", "ed_disposition", "disposition_status"));
        }

        Map<String, Object> cc = asMap(root.get("critical_care"));
        if (cc != null) {
            Map<String, Object> intervention = asMap(cc.get("critical_intervention"));
            if (intervention != null) {
                addDispositionLabel(labels, mapDispoEsc(firstStr(intervention, "DISPO_ESC", "dispo_esc", "disposition")));
            }
        }
        return new ArrayList<>(labels);
    }

    private static void addDispositionLabel(Set<String> labels, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String mapped = mapDispoEsc(raw.trim());
        if (mapped != null && !mapped.isBlank()) {
            labels.add(mapped);
        }
    }

    /** Maps review DISPO_* codes / status text to Select2 search terms. */
    static String mapDispoEsc(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (u) {
            case "DISPO_INPT_ADMIT", "INPT_ADMIT", "ADMIT", "ADMISSION", "INPATIENT" -> "Admit";
            case "DISPO_EXT_TRANSFER", "EXT_TRANSFER", "TRANSFER" -> "Transfer";
            case "DISPO_HOME", "HOME", "DISCHARGED", "DISCHARGE", "DC" -> "Discharge";
            case "DISPO_AMA", "AMA" -> "AMA";
            case "DISPO_EXPIRED", "EXPIRED", "DECEASED" -> "Expired";
            default -> {
                // Pass through human text (e.g. "Discharged", "Admit to floor")
                if (raw.contains("_") && raw.toUpperCase(Locale.ROOT).startsWith("DISPO")) {
                    yield raw.replace("DISPO_", "").replace('_', ' ').trim();
                }
                yield raw.trim();
            }
        };
    }

    /** ED EM Supplemental form selections for {@link ED_EMFormPlaywrightApplier}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toEdSelections(Map<String, Object> resumePayload) {
        Map<String, Object> selections = new LinkedHashMap<>();
        Map<String, Object> root = resolveResumeRoot(resumePayload);
        if (root.isEmpty()) {
            return selections;
        }
        Map<String, Object> ed = asMap(root.get("ed"));
        if (ed == null) {
            return selections;
        }
        Map<String, Object> copa = asMap(ed.get("copa"));
        Map<String, Object> data = asMap(ed.get("data"));
        Map<String, Object> risk = asMap(ed.get("risk"));

        for (Map.Entry<String, String> field : FormFieldConstants.NAME_TO_LABEL.entrySet()) {
            String fieldName = field.getKey();
            String label = field.getValue();
            Object raw = null;
            if (fieldName.startsWith("problem.")) {
                raw = valueByLabel(copa, label);
            } else if (fieldName.startsWith("risk.")) {
                raw = valueByLabel(risk, label);
            } else if (fieldName.startsWith("data.")) {
                raw = resolveDataField(fieldName, label, data);
            }
            if (raw != null) {
                selections.put(fieldName, normalizeSelectionValue(raw));
            }
        }
        return selections;
    }

    /**
     * True when resume JSON indicates Critical Care billing
     * ({@code billing_type=critical_care} or a non-null {@code critical_care} object).
     */
    public static boolean shouldUseCriticalCareForm(Map<String, Object> resumePayload) {
        Map<String, Object> root = resolveResumeRoot(resumePayload);
        if (root.isEmpty()) {
            return false;
        }
        String billingType = str(root, "billing_type");
        if (billingType != null && "critical_care".equalsIgnoreCase(billingType.trim())) {
            return true;
        }
        Object cc = root.get("critical_care");
        if (cc == null || "null".equalsIgnoreCase(String.valueOf(cc).trim())) {
            return false;
        }
        if (cc instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /**
     * Flattens {@code critical_care} JSON into form field names for
     * {@link com.wl.zotecAgent.selection.ED_EMFormPlaywrightApplier#applyCriticalCareSelections}.
     * <p>
     * Examples: {@code critical_care.critical_care_time}, {@code critical_care.critical_illness.RESP},
     * {@code critical_care.critical_intervention.NEURO_CTRL}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toCriticalCareSelections(Map<String, Object> resumePayload) {
        Map<String, Object> selections = new LinkedHashMap<>();
        Map<String, Object> root = resolveResumeRoot(resumePayload);
        if (root.isEmpty()) {
            return selections;
        }
        Map<String, Object> cc = asMap(root.get("critical_care"));
        if (cc == null || cc.isEmpty()) {
            return selections;
        }

        Object time = cc.get("critical_care_time");
        if (time != null) {
            String t = String.valueOf(time).trim();
            if (!t.isEmpty() && !"null".equalsIgnoreCase(t)) {
                selections.put("critical_care.critical_care_time", t);
            }
        }

        Map<String, Object> illness = asMap(cc.get("critical_illness"));
        if (illness != null) {
            for (Map.Entry<String, Object> e : illness.entrySet()) {
                String key = e.getKey();
                Object val = e.getValue();
                if (key == null || val == null) {
                    continue;
                }
                String field = "critical_care.critical_illness." + key;
                if ("OTHER".equalsIgnoreCase(key)) {
                    String text = String.valueOf(val).trim();
                    if (!text.isEmpty() && !"null".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                        selections.put(field, text);
                    }
                } else if (isTruthyCriticalFlag(val)) {
                    selections.put(field, Boolean.TRUE);
                }
            }
        }

        Map<String, Object> intervention = asMap(cc.get("critical_intervention"));
        if (intervention != null) {
            for (Map.Entry<String, Object> e : intervention.entrySet()) {
                String key = e.getKey();
                Object val = e.getValue();
                if (key == null || val == null) {
                    continue;
                }
                String field = "critical_care.critical_intervention." + key;
                String s = String.valueOf(val).trim();
                if (s.isEmpty() || "null".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
                    continue;
                }
                selections.put(field, s);
            }
        }
        return selections;
    }

    private static boolean isTruthyCriticalFlag(Object val) {
        if (val instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(val).trim();
        return "true".equalsIgnoreCase(s) || "check".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)
                || "1".equals(s) || "y".equalsIgnoreCase(s);
    }

    @SuppressWarnings("unchecked")
    public static Set<String> extractCptCodes(Map<String, Object> resumePayload) {
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> entry : extractCptEntries(resumePayload)) {
            String code = firstStr(entry, "code");
            if (code != null) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * Ordered CPT rows from JSON ({@code code}, {@code modifier}, {@code units},
     * {@code diagnoses}, {@code description}, {@code servicelocation}, {@code pos}).
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractCptEntries(Map<String, Object> resumePayload) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> root = resolveResumeRoot(resumePayload);
        if (root.isEmpty()) {
            return entries;
        }
        Set<String> seen = new HashSet<>();
        Object cptObj = root.get("cpt");
        if (cptObj instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> raw = (Map<String, Object>) m;
                String code = firstStr(raw, "code", "cpt_code");
                if (code == null) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("code", code.trim());
                String modifier = firstStr(raw, "modifier", "modifiers");
                if (modifier != null) {
                    entry.put("modifier", modifier.trim());
                }
                Object units = raw.get("units");
                if (raw.containsKey("units")) {
                    // Keep null so Service can apply Zotec-required default "1"
                    if (units != null && !"null".equalsIgnoreCase(String.valueOf(units).trim())) {
                        entry.put("units", units);
                    } else {
                        entry.put("units", null);
                    }
                }
                String diagnoses = firstStr(raw, "diagnoses", "diagnosis", "dx");
                if (diagnoses != null) {
                    entry.put("diagnoses", diagnoses.trim());
                }
                String description = firstStr(raw, "description", "desc");
                if (description != null) {
                    entry.put("description", description.trim());
                }
                String serviceLocation = firstStr(raw, "servicelocation", "service_location", "serviceLocation");
                if (serviceLocation != null) {
                    entry.put("servicelocation", serviceLocation.trim());
                }
                String pos = firstStr(raw, "pos", "pos_code", "place_of_service", "placeOfService");
                if (pos != null) {
                    entry.put("pos", pos.trim());
                }
                entries.add(entry);
                seen.add(code.trim());
            }
        }
        Map<String, Object> ed = asMap(root.get("ed"));
        if (ed != null) {
            String em = str(ed, "em_cpt_code");
            if (em != null && !seen.contains(em.trim())) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("code", em.trim());
                entries.add(entry);
                seen.add(em.trim());
            }
        }
        String ccCpt = criticalCareCptCode(root.get("critical_care"));
        if (ccCpt != null && !seen.contains(ccCpt)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", ccCpt);
            entry.put("description", "Critical Care");
            entries.add(entry);
        }
        return entries;
    }

    /** Maps non-null critical_care payload to a CPT code (default 99291). */
    private static String criticalCareCptCode(Object cc) {
        if (cc == null || "null".equalsIgnoreCase(String.valueOf(cc).trim())) {
            return null;
        }
        if (cc instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) m;
            String code = firstStr(map, "code", "cpt", "cpt_code", "em_cpt_code");
            if (code != null) {
                return code.trim();
            }
            return "99291";
        }
        if (cc instanceof Boolean b) {
            return b ? "99291" : null;
        }
        String s = String.valueOf(cc).trim();
        if (s.isEmpty() || "false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s)) {
            return null;
        }
        if (s.matches("\\d{5}")) {
            return s;
        }
        if ("true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)) {
            return "99291";
        }
        return "99291";
    }

    @SuppressWarnings("unchecked")
    public static Set<String> extractIcdCodes(Map<String, Object> resumePayload) {
        return new HashSet<>(extractIcdCodeList(resumePayload));
    }

    /** ICD codes in JSON order (1-based pointers for CPT Diagnoses column). */
    @SuppressWarnings("unchecked")
    public static List<String> extractIcdCodeList(Map<String, Object> resumePayload) {
        List<String> codes = new ArrayList<>();
        Map<String, Object> root = resolveResumeRoot(resumePayload);
        if (root.isEmpty()) {
            return codes;
        }
        Object icdObj = root.get("icd");
        if (!(icdObj instanceof List<?> list)) {
            return codes;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                String code = firstStr((Map<String, Object>) m, "codeName", "code", "icd_code");
                if (code != null) {
                    codes.add(code.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return codes;
    }

    /**
     * Resolves the coding payload root from either a flat resume JSON or a review
     * API envelope with {@code reviewed_result} (and optionally nested {@code data}).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveResumeRoot(Map<String, Object> resumePayload) {
        if (resumePayload == null || resumePayload.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> reviewed = asMap(resumePayload.get("reviewed_result"));
        if (reviewed != null && !reviewed.isEmpty()) {
            if (hasCodingKeys(reviewed)) {
                return reviewed;
            }
            Map<String, Object> nestedData = asMap(reviewed.get("data"));
            if (nestedData != null && !nestedData.isEmpty() && hasCodingKeys(nestedData)) {
                return nestedData;
            }
            if (hasCodingKeys(resumePayload)) {
                return resumePayload;
            }
            return reviewed;
        }

        Map<String, Object> topData = asMap(resumePayload.get("data"));
        if (topData != null && !topData.isEmpty() && hasCodingKeys(topData)
                && !hasCodingKeys(resumePayload)) {
            return topData;
        }

        return resumePayload;
    }

    private static boolean hasCodingKeys(Map<String, Object> map) {
        return map.containsKey("patient") || map.containsKey("cpt") || map.containsKey("icd")
                || map.containsKey("ed");
    }

    private static Object resolveDataField(String fieldName, String label, Map<String, Object> data) {
    	 if (data == null) {
             return null;
         }
    	 if (fieldName.startsWith("data.order_review_of_test_results_")) {
    		    return nestedTestValue(data, ORDER_TESTS, label);
    		}

    		if (fieldName.startsWith("data.indep_interpret_of_test_by_another_")) {
    		    return nestedTestValue(data, INTERPRET_TESTS, label);
    		}

    		return valueByLabel(data, label);
    }

    @SuppressWarnings("unchecked")
    private static Object nestedTestValue(Map<String, Object> data, String parentLabel, String childLabel) {
        Map<String, Object> parent = findMapByLabel(data, parentLabel);
        if (parent == null) {
            return null;
        }
        return valueByLabel(parent, childLabel);
    }

    private static Map<String, Object> buildProviders(Map<String, Object> patient) {
        Map<String, Object> providers = new LinkedHashMap<>();
        String supervising = str(patient, "supervising_provider");
        String rendering = str(patient, "rendering_provider");
        if (supervising != null) {
            Map<String, Object> sup = new LinkedHashMap<>();
            sup.put("name", supervising);
            sup.put("title", titleFromName(supervising));
            providers.put("supervising", sup);
        }
        if (rendering != null) {
            Map<String, Object> ren = new LinkedHashMap<>();
            ren.put("name", rendering);
            ren.put("title", titleFromName(rendering));
            providers.put("rendering", ren);
        }
        return providers;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> buildDiagnoses(Map<String, Object> resumePayload) {
        List<Object> out = new ArrayList<>();
        Object icdObj = resumePayload.get("icd");
        if (!(icdObj instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> row = new LinkedHashMap<>();
                String code = firstStr((Map<String, Object>) m, "codeName", "code", "icd_code");
                if (code != null) {
                    row.put("code", code);
                }
                String desc = str((Map<String, Object>) m, "description");
                if (desc != null) {
                    row.put("description", desc);
                }
                if (!row.isEmpty()) {
                    out.add(row);
                }
            }
        }
        return out;
    }

    private static String titleFromName(String name) {
        if (name == null) {
            return "";
        }
        String u = name.toUpperCase(Locale.ROOT);
        if (u.contains(", PA") || u.contains(" PA,") || u.endsWith(" PA")) {
            return "PA";
        }
        if (u.contains(", NP") || u.contains(" NP,") || u.endsWith(" NP")) {
            return "NP";
        }
        return "MD";
    }

    private static Object normalizeSelectionValue(Object raw) {
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("check".equals(s) || "checked".equals(s) || "true".equals(s)) {
            return Boolean.TRUE;
        }
        if ("uncheck".equals(s) || "unchecked".equals(s) || "false".equals(s)) {
            return Boolean.FALSE;
        }
        return String.valueOf(raw).trim();
    }

    @SuppressWarnings("unchecked")
    private static Object valueByLabel(Map<String, Object> section, String label) {
        if (section == null || label == null) {
            return null;
        }
        String want = normalizeLabel(label);
        for (Map.Entry<String, Object> e : section.entrySet()) {
            if (normalizeLabel(e.getKey()).equals(want)) {
                return e.getValue();
            }
        }
        for (Map.Entry<String, Object> e : section.entrySet()) {
            String key = normalizeLabel(e.getKey());
            if (key.contains(want) || want.contains(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findMapByLabel(Map<String, Object> section, String label) {
        Object v = valueByLabel(section, label);
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    private static String normalizeLabel(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String str(Map<String, Object> m, String... keys) {
        return firstStr(m, keys);
    }

    private static String firstStr(Map<String, Object> m, String... keys) {
        if (m == null) {
            return null;
        }
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static void copyIfMap(Map<String, Object> target, Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Map<String, Object> section = asMap(source.get(key));
            if (section != null && !section.isEmpty()) {
                target.put(key, new LinkedHashMap<>(section));
            }
        }
    }

    private static Map<String, Object> mergeMap(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (overlay != null) {
            merged.putAll(overlay);
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static String firstCptServiceLocation(Map<String, Object> resumePayload) {
        Object cptObj = resumePayload.get("cpt");
        if (!(cptObj instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                String loc = firstStr((Map<String, Object>) m, "servicelocation", "service_location");
                if (loc != null) {
                    return loc;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String firstCptPos(Map<String, Object> resumePayload) {
        Object cptObj = resumePayload.get("cpt");
        if (!(cptObj instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                String pos = firstStr((Map<String, Object>) m, "pos", "pos_code", "place_of_service",
                        "placeOfService");
                if (pos != null) {
                    return pos;
                }
            }
        }
        return null;
    }
}
