package com.wl.zotecAgent.edem;

import java.util.*;

/**
 * Decides which form fields to select based on output.json + Excel (COPA, DATA,
 * RISK). Returns a map: field name -> value ("0"/"1"/"2"/"3" for radios, true
 * for checkboxes).
 */
public class ED_EMFormDecisionService {

    /** Maps COPA Problem Category (partial match) to form field name. */
    private static final Map<String, String> COPA_TO_FIELD = Map.ofEntries(
	    Map.entry("self-limited", "problem.self_limited_minor_problems"),
	    Map.entry("minor problem", "problem.self_limited_minor_problems"),
	    Map.entry("stable, chronic", "problem.stable_chronic"), Map.entry("stable, acute", "problem.stable_acute"),
	    Map.entry("acute, uncomplicated", "problem.acute_uncomplicated"),
	    Map.entry("chronic illness w/ exacerbation", "problem.chronic_exac_progr_side_effects"),
	    Map.entry("chronic illness with exacerbation", "problem.chronic_exac_progr_side_effects"),
	    Map.entry("acute, complicated", "problem.acute_complicated"),
	    Map.entry("acute illness w/ systemic", "problem.acute_systemic_symptoms"),
	    Map.entry("undiagnosed new problem", "problem.undiag_prob_uncertain_prog"),
	    Map.entry("chronic severe", "problem.chronic_severe_exab_progr_se"),
	    Map.entry("threat to life", "problem.acute_or_chronic_threat_to_life_and_body"));

    /** Maps RISK element keywords to form field name. */
    private static final Map<String, String> RISK_TO_FIELD = Map.ofEntries(Map.entry("minimal", "risk.minimal"),
	    Map.entry("otc", "risk.otc_medications"),
	    Map.entry("rest, gargle, bandage", "risk.rest_gargle_bandage_dressing"),
	    Map.entry("extremity x-ray", "risk.rad_exposure_extremity_xr"),
	    Map.entry("rigid musculoskeletal", "risk.rigid_musculoskeletal_immobilization"),
	    Map.entry("splint", "risk.rigid_musculoskeletal_immobilization"),
	    Map.entry("cast", "risk.rigid_musculoskeletal_immobilization"),
	    Map.entry("prescription drug", "risk.prescription_drugs"),
	    Map.entry("iv fluid", "risk.iv_fluids_with_or_without_additives"),
	    Map.entry("ct scan", "risk.ct_scan_with_iv_contrast"),
	    Map.entry("iv contrast", "risk.ct_scan_with_iv_contrast"),
	    Map.entry("parenteral controlled", "risk.parenteral_drugs"));

    private final ED_EMExcelReader excelReader;

    public ED_EMFormDecisionService(ED_EMExcelReader excelReader) {
	this.excelReader = excelReader;
    }

    /**
     * Returns selections: field name -> value (String for radios, Boolean for
     * checkboxes).
     */
    public Map<String, Object> decideSelections(Map<String, Object> outputJson) {
	Map<String, Object> selections = new LinkedHashMap<>();

	// PROBLEM (COPA)
	applyCopaSelections(selections, outputJson);

	// DATA
	applyDataSelections(selections, outputJson);

	// RISK
	applyRiskSelections(selections, outputJson);

	return selections;
    }

    @SuppressWarnings("unchecked")
    private void applyCopaSelections(Map<String, Object> selections, Map<String, Object> data) {
	List<String> diagnoses = getDiagnoses(data);
	String combined = String.join(" ", diagnoses).toLowerCase();
	String edCourse = opt(data, "ed_course").toLowerCase();
	combined = combined + " " + edCourse;

	// Defaults
	selections.put("problem.self_limited_minor_problems", "0");
	selections.put("problem.stable_chronic", "0");
	selections.put("problem.stable_acute", false);
	selections.put("problem.acute_uncomplicated", false);
	selections.put("problem.chronic_exac_progr_side_effects", false);
	selections.put("problem.acute_complicated", false);
	selections.put("problem.acute_systemic_symptoms", false);
	selections.put("problem.undiag_prob_uncertain_prog", false);
	selections.put("problem.chronic_severe_exab_progr_se", false);
	selections.put("problem.acute_or_chronic_threat_to_life_and_body", false);

	// Find best-matching COPA row(s) - take highest MDM level match to avoid
	// over-matching
	List<Map<String, String>> copa = excelReader.getCopaData();
	int mdmRank = 0; // 0=unused, 1=straightforward, 2=low, 3=moderate, 4=high
	String bestField = null;
	Object bestVal = null;
	for (Map<String, String> row : copa) {
	    String mdm = getVal(row, "mdm");
	    String category = getVal(row, "problem", "category");
	    String examples = getVal(row, "examples");
	    String cptDef = getVal(row, "cpt", "definition");
	    String catLower = (category != null ? category : "").toLowerCase();
	    String exLower = (examples != null ? examples : "").toLowerCase();
	    String cptLower = (cptDef != null ? cptDef : "").toLowerCase();

	    boolean match = matchesDiagnosis(combined, diagnoses, catLower, exLower, cptLower);
	    if (!match || category == null)
		continue;

	    int r = mdmRank(mdm);
	    if (r > mdmRank) {
		mdmRank = r;
		bestField = mapCopaToField(category);
		bestVal = (bestField != null && (bestField.equals("problem.self_limited_minor_problems")
			|| bestField.equals("problem.stable_chronic"))) ? "1" : Boolean.TRUE;
	    }
	}
	if (bestField != null && bestVal != null)
	    selections.put(bestField, bestVal);

	// Fallback rules - when no COPA row matched
	if (bestField == null) {
	    if (combined.contains("fracture") && !combined.contains("displaced") && !combined.contains("complicate"))
		selections.put("problem.acute_uncomplicated", true);
	    else if (combined.contains("diverticulitis")
		    || (combined.contains("abdominal") && combined.contains("infection")))
		selections.put("problem.acute_systemic_symptoms", true);
	}
    }

    private static boolean matchesDiagnosis(String combined, List<String> diagnoses, String catLower, String exLower,
	    String cptLower) {
	for (String dx : diagnoses) {
	    if (dx == null || dx.isBlank())
		continue;
	    String dxLower = dx.toLowerCase();
	    if (exLower.contains(dxLower) || cptLower.contains(dxLower))
		return true;
	    if (dxLower.contains("fracture") && (exLower.contains("uncomplicated") || exLower.contains("ankle")
		    || exLower.contains("sprain") || exLower.contains("laceration")))
		return true;
	    if (dxLower.contains("diverticulitis")) {
		if (exLower.contains("abdominal") && !exLower.contains("well hydrated") && !exLower.contains("no meds"))
		    return true;
		if (catLower.contains("acute") && catLower.contains("systemic"))
		    return true;
		if (catLower.contains("chronic") && catLower.contains("exacerbation"))
		    return true;
	    }
	}
	if (combined.contains("fracture")
		&& (exLower.contains("uncomplicated") || exLower.contains("ankle") || exLower.contains("sprain")))
	    return true;
	if (combined.contains("diverticulitis")) {
	    if (catLower.contains("acute") && catLower.contains("systemic"))
		return true;
	    if (catLower.contains("chronic") && catLower.contains("exacerbation"))
		return true;
	}
	return false;
    }

    private static String getVal(Map<String, String> row, String... keyParts) {
	for (Map.Entry<String, String> e : row.entrySet()) {
	    if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank())
		continue;
	    String k = e.getKey().toLowerCase();
	    boolean all = true;
	    for (String part : keyParts) {
		if (part == null || part.isBlank())
		    continue;
		if (!k.contains(part.toLowerCase())) {
		    all = false;
		    break;
		}
	    }
	    if (all && keyParts.length > 0)
		return e.getValue();
	}
	for (String part : keyParts) {
	    if (part == null || part.isBlank())
		continue;
	    for (Map.Entry<String, String> e : row.entrySet()) {
		if (e.getKey() != null && e.getKey().toLowerCase().contains(part.toLowerCase()) && e.getValue() != null
			&& !e.getValue().isBlank())
		    return e.getValue();
	    }
	}
	return null;
    }

    private static int mdmRank(String mdm) {
	if (mdm == null)
	    return 0;
	String m = mdm.toLowerCase();
	if (m.contains("high") || m.contains("threat"))
	    return 4;
	if (m.contains("moderate"))
	    return 3;
	if (m.contains("low"))
	    return 2;
	if (m.contains("straightforward"))
	    return 1;
	return 0;
    }

    private String mapCopaToField(String category) {
	String c = category.toLowerCase();
	for (Map.Entry<String, String> e : COPA_TO_FIELD.entrySet()) {
	    if (c.contains(e.getKey()))
		return e.getValue();
	}
	return null;
    }

    @SuppressWarnings("unchecked")
    private void applyDataSelections(Map<String, Object> selections, Map<String, Object> data) {
	selections.put("data.review_of_external_notes", "0");
	selections.put("data.order_review_of_test_results_ekg", "0");
	selections.put("data.order_review_of_test_results_xray", "0");
	selections.put("data.order_review_of_test_results_poc_us", "0");
	selections.put("data.order_review_of_test_results_other", "0");
	selections.put("data.indep_interpret_of_test_by_another_xray", "0");

	boolean hasExternalReview = hasExternalRecordsReview(data);
	if (hasExternalReview)
	    selections.put("data.review_of_external_notes", "2");

	boolean hasXray = hasImaging(data, "xr", "x-ray", "ct", "rad");
	if (hasXray) {
	    selections.put("data.order_review_of_test_results_xray", "1");
	    selections.put("data.indep_interpret_of_test_by_another_xray", "2");
	}

	boolean hasLabs = hasLabs(data);
	if (hasLabs)
	    selections.put("data.order_review_of_test_results_other", "3");
    }

    @SuppressWarnings("unchecked")
    private void applyRiskSelections(Map<String, Object> selections, Map<String, Object> data) {
	String combined = getCombinedText(data).toLowerCase();

	if (combined.contains("tylenol") || combined.contains("ibuprofen") || combined.contains("acetaminophen"))
	    selections.put("risk.otc_medications", true);
	if (combined.contains("extremity x") || combined.contains("foot x") || combined.contains("ankle x")
		|| (combined.contains("xr") && combined.contains("foot")))
	    selections.put("risk.rad_exposure_extremity_xr", true);
	if (combined.contains("buddy tap") || combined.contains("splint") || combined.contains("immobilization")
		|| combined.contains("cast"))
	    selections.put("risk.rigid_musculoskeletal_immobilization", true);
	if (combined.contains("iv fluid") || combined.contains("peripheral iv") || combined.contains("iv continuous"))
	    selections.put("risk.iv_fluids_with_or_without_additives", true);
	if (combined.contains("prescription") || combined.contains("iv pain") || combined.contains("iv med")
		|| combined.contains("morphine") || combined.contains("dilaudid"))
	    selections.put("risk.prescription_drugs", true);
	if (combined.contains("ct ") && (combined.contains("iv") || combined.contains("contrast")
		|| combined.contains("abdomen") || combined.contains("pelvis")))
	    selections.put("risk.rad_exposure_ct_or_xr_head_neck_torso", true);
    }

    @SuppressWarnings("unchecked")
    private static List<String> getDiagnoses(Map<String, Object> data) {
	List<String> out = new ArrayList<>();
	Object d = data.get("diagnoses");
	if (d instanceof List<?> list) {
	    for (Object o : list) {
		if (o instanceof Map<?, ?> m) {
		    Object desc = ((Map<?, ?>) o).get("description");
		    if (desc != null)
			out.add(desc.toString());
		}
	    }
	}
	Object sec = data.get("secondary_diagnoses");
	if (sec instanceof List<?> list) {
	    for (Object o : list) {
		if (o instanceof Map<?, ?> m) {
		    Object desc = m.get("description");
		    if (desc != null)
			out.add(desc.toString());
		}
	    }
	}
	Object ed = data.get("ed_diagnosis");
	if (ed instanceof List<?> list) {
	    for (Object o : list) {
		if (o instanceof Map<?, ?> m) {
		    Object diag = ((Map<?, ?>) o).get("diagnosis");
		    if (diag != null)
			out.add(diag.toString());
		}
	    }
	}
	Object clinical = data.get("clinical_impressions");
	if (clinical != null)
	    out.add(clinical.toString());
	return out;
    }

    private static boolean hasExternalRecordsReview(Map<String, Object> data) {
	Object ext = data.get("external_records_reviewed");
	return ext != null && !ext.toString().isBlank() && !ext.toString().equalsIgnoreCase("N/A");
    }

    @SuppressWarnings("unchecked")
    private static boolean hasImaging(Map<String, Object> data, String... keywords) {
	Object img = data.get("imaging");
	if (img != null) {
	    String s = img.toString().toLowerCase();
	    for (String k : keywords)
		if (s.contains(k))
		    return true;
	}
	Object orders = data.get("ed_imaging_orders");
	if (orders instanceof List<?> list) {
	    for (Object o : list) {
		if (o instanceof Map<?, ?> m) {
		    Object desc = m.get("order");
		    if (desc != null) {
			String s = desc.toString().toLowerCase();
			for (String k : keywords)
			    if (s.contains(k))
				return true;
		    }
		}
	    }
	}
	Object allOrders = data.get("ed_all_orders");
	if (allOrders instanceof List<?> list) {
	    for (Object o : list) {
		if (o instanceof Map<?, ?> m) {
		    Object desc = m.get("description");
		    if (desc != null) {
			String s = desc.toString().toLowerCase();
			if (s.contains("ct ") || s.contains("x-ray") || s.contains("xr ") || s.contains("mri")
				|| s.contains("imaging"))
			    return true;
		    }
		}
	    }
	}
	Object notes = data.get("notes");
	if (notes instanceof Map<?, ?> n) {
	    Object unparsed = n.get("unparsed_text");
	    if (unparsed != null) {
		String s = unparsed.toString().toLowerCase();
		if (s.contains("ct ") || s.contains("x-ray") || s.contains("imaging"))
		    return true;
	    }
	}
	return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasLabs(Map<String, Object> data) {
	Object res = data.get("results");
	if (res != null && !res.toString().isBlank() && !res.toString().equalsIgnoreCase("None"))
	    return true;
	Object orders = data.get("ed_all_orders");
	if (orders instanceof List<?> list) {
	    for (Object o : list) {
		if (o instanceof Map<?, ?> m) {
		    Object desc = m.get("description");
		    if (desc != null) {
			String s = desc.toString().toLowerCase();
			if (s.contains("lab") || s.contains("cbc") || s.contains("bmp") || s.contains("metabolic")
				|| s.contains("culture"))
			    return true;
		    }
		}
	    }
	}
	return false;
    }

    @SuppressWarnings("unchecked")
    private static String getCombinedText(Map<String, Object> data) {
	StringBuilder sb = new StringBuilder();
	sb.append(opt(data, "medical_decision_making")).append(" ");
	sb.append(opt(data, "discharge_instructions")).append(" ");
	sb.append(opt(data, "ed_course")).append(" ");
	Object notes = data.get("notes");
	if (notes instanceof Map<?, ?> n)
	    sb.append(opt(n, "unparsed_text")).append(" ");
	Object meds = data.get("ed_medication_orders");
	if (meds instanceof List<?> list) {
	    for (Object o : list) {
		if (o instanceof Map<?, ?> m)
		    sb.append(opt(m, "order")).append(" ");
	    }
	}
	Object orders = data.get("ed_all_orders");
	if (orders instanceof List<?> list) {
	    for (Object o : list) {
		if (o instanceof Map<?, ?> m)
		    sb.append(opt(m, "description")).append(" ");
	    }
	}
	Object img = data.get("imaging");
	if (img != null)
	    sb.append(img).append(" ");
	return sb.toString();
    }

    private static String opt(Map<?, ?> m, String key) {
	Object v = m.get(key);
	return v != null ? v.toString() : "";
    }
}
