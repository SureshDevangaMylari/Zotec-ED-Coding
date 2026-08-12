package wl.ai;

import com.google.gson.Gson;
import com.wl.util.JsonFileUtil;

import java.io.File;
import java.util.Map;

/**
 * Clinical data extraction service. Owns only the clinical prompts — ALL API
 * calls go through LLMService.
 *
 * Usage: ClinicalExtractionService svc = new ClinicalExtractionService();
 * Map<String,Object> result = svc.extract(clinicalText); Map<String,Object>
 * imgResult = svc.extractFromImage(imageFile); Map<String,Object> b64Result =
 * svc.extractFromBase64(base64, "image/png");
 */
public class ClinicalExtractionService {

    private final LLMService llmService;
    private final Gson gson = new Gson();

    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are an expert medical coder and clinical document parser. Extract EVERY piece of data from the provided clinical text or image. Return ONLY a single valid JSON object (no explanations, no markdown fences).

            CORE RULES:
            - Output must be valid JSON only — no prose, no markdown fences.
            - Do NOT assign ICD-10 or CPT/procedure codes. Extract diagnosis and procedure descriptions as text only. Code assignment is handled by a separate service.
            - Extract EVERYTHING visible in the document — every heading, section, table, label, and value.
            - Every section heading or label in the image becomes a JSON key (snake_case, lowercase).
            - If a section contains a table, represent it as an array of objects with column names as keys.
            - If a section contains key-value pairs, represent as an object.
            - If a section contains free text, store as a string value.
            - If a section says "None", "NA", "No data filed", "Not on File", "Not Asked", store that exact text as the value.
            - Preserve dates in M/d/yyyy format (e.g. 2/19/2026). Convert other formats.
            - Use null ONLY for fields truly absent from the document. "--" in table cells → null.
            - Do NOT invent or guess values — use null instead.
            - Do NOT skip ANY section — if it is in the image, it must be in the JSON.

            SECTION-TO-KEY MAPPING (convert heading to snake_case):
              "ED Provider Notes" → "ed_provider_notes"
              "Attribution Key" → "attribution_key"
              "ED Course" → "ed_course"
              "Dictations" → "dictations"
              "ED Diagnosis" → "ed_diagnosis"
              "ED Disposition" → "ed_disposition"
              "Results" → "results"
              "Imaging Results" → "imaging_results"
              "ECG Results" → "ecg_results"
              "Code Onset/Outcome" → "code_onset_outcome"
              "Code Interventions/Drips/Airways" → "code_interventions_drips_airways"
              "Home Medications" → "home_medications"
              "Medication Administration" → "medication_administration"
              "ED Prescriptions" → "ed_prescriptions"
              "ED Medication Orders" → "ed_medication_orders"
              "Code, Iso, Restraint" → "code_iso_restraint"
              "ED Imaging Orders" → "ed_imaging_orders"
              "ED Micro, Lab, POCT" → "ed_micro_lab_poct"
              "ED All Orders" → "ed_all_orders"
              "Discharge Orders" → "discharge_orders"
              "Allergies" → "allergies"
              "Tetanus Up To Date" → "tetanus_up_to_date"
              "Medical History" → "medical_history"
              "Surgical History" → "surgical_history"
              "Social History" → "social_history"
              "Social Documentation" → "social_documentation"
              "Family Medical History" → "family_medical_history"
              "ED LDA Documentation" → "ed_lda_documentation"
              "ED Events" → "ed_events"
              "Follow-up Information" → "follow_up_information"
              "Discharge Instructions" → "discharge_instructions"
              "Discharge References/Attachments" → "discharge_references_attachments"
              "Communication Routing History" → "communication_routing_history"
              "ED Charges" → "ed_charges"
              "Mode of Arrival" → "mode_of_arrival"
              "History of Present Illness" → "history_of_present_illness"
              "Past Medical History" → "past_medical_history"
              "Past Surgical History" → "past_surgical_history"
              "Review of Systems" → "review_of_systems"
              "Physical Exam" → "physical_exam"
              "Vitals" → "vitals"
              "Labs" → "labs"
              "Imaging" → "imaging"
              "Medical Decision Making" → "medical_decision_making"
              "Clinical Impressions" → "clinical_impressions"
              "Procedures" → "procedures_detail"
              "Consultations" → "consultations"
              "Critical Care" → "critical_care"
              "Final Diagnoses" → "final_diagnoses"
              "Secondary Diagnoses" → "secondary_diagnoses"
              "Social Determinants of Health" → "social_determinants_of_health"
              "Disposition" → "disposition"
              "Prescription Management" → "prescription_management"
            - Any heading NOT listed above: still include it — convert to snake_case.
            - If the same heading appears multiple times, combine content into one key.

            TABLE EXTRACTION:
            - Tables with column headers → array of objects, each row = object with column names as snake_case keys.
              Example: "ED Diagnosis" table → "ed_diagnosis": [{"diagnosis":"Cardiac arrest","description":"Cardiac arrest","comment":null,"associated_orders":null}]
              Example: "Code Onset/Outcome" table → "code_onset_outcome": [{"date_and_time":"02/19/26 2227","cardiac_rhythm":"V-Fib","user":"AB",...}]
              Example: "Medication Administration" table → "medication_administration": [{"date_time":"02/19/2026 2210 PST","order":"EPINEPHrine 1 mg/10 mL","dose":"1 mg","route":"Intravenous","action":"Given","action_by":"Nichole Tyler, RN","comments":null}]
              Example: "ED Events" table → "ed_events": [{"date_time":"02/19/26 2154","event":"Patient arrived in ED","user":"KEELY, LAURIE","comments":null}]
              Example: "ED Charges" table → "ed_charges": [{"description":"HB EMERGENCY DEPT VISIT 3","code":"6000006","date":"02/19/2026","service_prov":"Henson, Priscilla","modifiers":null,"qty":1,"status":"Filed"}]
              Example: "ED LDA Documentation" table → "ed_lda_documentation": [{"date":"2/19/2026","time":"22:04","event":"Peripheral IV","details":"...","user":"Sedrick Abrams, RN"}]
            - "--" or empty cells → null

            PROVIDER ROLE DETECTION (critical for billing):
            - "md" = MD, DO, M.D., D.O. (physicians only)
            - "pa" = PA, PA-C, PAC, NP, N.P., APP, APRN (mid-level providers only)
            - IGNORE: RN, LPN, CNA, RT, MA, EMT, Paramedic, Tech, Secretary, Unit Clerk — not billing providers.
            - providers.all_providers: only "md" or "pa" role entries.
            - Placement: Both MD+PA → supervising=MD, rendering=PA. Only MD → rendering=MD, supervising=null. Only PA → rendering=PA, supervising=null.

            ED DISPOSITION (critical for admit logic):
            - Extract from "ED Disposition" table: status, condition, comment.
            - "Discharged"/"Expired"/"Left AMA"/"Transfer" → encounter.admitted = false, encounter.status = "expired"/"discharged" etc.
            - "Admitted" → encounter.admitted = true, encounter.status = "admitted".

            DIAGNOSIS / CLINICAL IMPRESSION:
            - Extract diagnosis DESCRIPTIONS only (e.g. "Acute chest pain", "Muscle strain"). Do NOT assign ICD codes.
            - billing.icd_codes: only include codes that appear verbatim in the document. If document shows descriptions without codes, store descriptions as strings.
            - diagnoses array: { "description": "...", "type": "primary|secondary|final" } — no code field unless explicitly written in document.

            SOCIAL HISTORY sub-sections:
            - Extract each sub-category: tobacco_history (smoking_status, smokeless_tobacco_use), alcohol_history (alcohol_use_status), drug_use (drug_use_status), sexual_activity (sexually_active), other_factors.
            - Store as nested object under social_history.

            PHYSICAL EXAM:
            - Store vitals table separately under "vitals" as array of timestamped readings: [{"timestamp":"02/19/26 2211","bp":"108/42","pulse":null,"temp":null,"resp":null,"spo2":"67%"}, ...]
            - Store exam findings text under "physical_exam" as object with body-system keys: {"general":"unresponsive","head":"Normocephalic","eyes":"Pupils reactive...","cardiac":"Cool extremities...","neuro":"GCS 3",...}

            OUTPUT JSON (always include these keys, plus any additional sections found):
            {
              "identifiers": { "mrn": null, "account_number": null, "encounter_number": null, "patient_id": null },
              "patient": { "name": null, "dob": null, "age": null, "sex": null },
              "encounter": { "date_of_service": null, "admit_date": null, "encounter_date_time": null, "encounter_location": null, "service": null, "status": null, "admitted": null },
              "ed_provider_notes": { "author": null, "service": null, "date_of_service": null, "status": null, "editor": null },
              "attribution_key": [],
              "mode_of_arrival": null,
              "history_of_present_illness": null,
              "past_medical_history": null,
              "past_surgical_history": null,
              "social_history": { "tobacco_history": { "smoking_status": null, "smokeless_tobacco_use": null }, "alcohol_history": { "alcohol_use_status": null }, "drug_use": { "drug_use_status": null }, "sexual_activity": { "sexually_active": null }, "other_factors": null, "counseling": null },
              "social_documentation": null,
              "family_medical_history": null,
              "review_of_systems": null,
              "physical_exam": { "general": null, "head": null, "eyes": null, "ent": null, "cardiac": null, "respiratory": null, "gi": null, "musculoskeletal": null, "skin": null, "neuro": null },
              "vitals": [],
              "labs": null,
              "imaging": null,
              "imaging_results": null,
              "ecg_results": null,
              "medical_decision_making": null,
              "source": null,
              "external_records_reviewed": null,
              "ekg": null,
              "ed_course": null,
              "clinical_impressions": null,
              "consultations": null,
              "critical_care": null,
              "sepsis_protocol": null,
              "procedures_detail": [],
              "final_diagnoses": { "primary": [], "secondary": [] },
              "secondary_diagnoses": null,
              "social_determinants_of_health": null,
              "disposition": null,
              "prescription_management": null,
              "electronic_signature": null,
              "attestation": null,
              "dictations": null,
              "ed_course_summary": null,
              "ed_diagnosis": [],
              "ed_disposition": { "status": null, "condition": null, "comment": null },
              "results": null,
              "code_onset_outcome": [],
              "code_interventions_drips_airways": [],
              "home_medications": null,
              "medication_administration": [],
              "ed_prescriptions": null,
              "ed_medication_orders": [],
              "code_iso_restraint": null,
              "ed_imaging_orders": null,
              "ed_micro_lab_poct": null,
              "ed_all_orders": null,
              "discharge_orders": null,
              "allergies": null,
              "tetanus_up_to_date": null,
              "medical_history": null,
              "surgical_history": null,
              "ed_lda_documentation": [],
              "ed_events": [],
              "follow_up_information": null,
              "discharge_instructions": null,
              "discharge_references_attachments": null,
              "communication_routing_history": null,
              "ed_charges": [],
              "coding": { "profile": null, "service_location": null, "place_of_service": null, "department": null, "authorization_number": null },
              "providers": {
                "supervising": { "name": null, "npi": null, "credential": null },
                "rendering": { "name": null, "npi": null, "credential": null },
                "physician_assistant": { "name": null, "npi": null, "credential": null },
                "all_providers": []
              },
              "billing": { "payor": null, "icd_codes": [], "accident_code": null, "accident_date": null },
              "procedures": [],
              "diagnoses": [],
              "visit": { "chief_complaint": null, "hpi": null },
              "notes": { "unparsed_text": null }
            }

            IMPORTANT: The above is the BASE structure. If you find ANY section/heading in the image NOT listed above, ADD it as a new key (snake_case) at the top level. Extract EVERY single thing. Do NOT omit anything.
            """;

    public static final String DEFAULT_IMAGE_USER_PROMPT =
            "Extract EVERY piece of data from this image — do NOT skip any section. " +
            "Every section heading becomes a snake_case JSON key. Every table becomes an array of objects. " +
            "Include ALL of these if present: " +
            "patient header (name, MRN, encounter date), " +
            "ED Provider Notes (author, service, date, status, editor), " +
            "Attribution Key, Mode of Arrival, History of Present Illness, " +
            "Past Medical History, Past Surgical History, Social History (tobacco, alcohol, drug use, sexual activity), " +
            "Social Documentation, Family Medical History, Review of Systems, " +
            "Physical Exam (body-system findings), Vitals table (timestamped readings), Labs, " +
            "Imaging, Imaging Results, ECG Results, Medical Decision Making, EKG, Source, External Records, " +
            "ED Course (narrative), Clinical Impressions, Consultations, Critical Care, " +
            "Procedures (intubation details, medications, ETT, etc.), " +
            "Final Diagnoses (primary + secondary), Social Determinants of Health, Disposition, " +
            "Prescription Management, Electronic Signature, Attestation, Dictations, " +
            "ED Diagnosis table, ED Disposition table (status/condition/comment), Results, " +
            "Code Onset/Outcome table (cardiac rhythms), Code Interventions/Drips/Airways table, " +
            "Home Medications, Medication Administration table, ED Prescriptions, ED Medication Orders table, " +
            "Code/Iso/Restraint, ED Imaging Orders, ED Micro/Lab/POCT, ED All Orders, Discharge Orders, " +
            "Allergies, Tetanus Up To Date, Medical History, Surgical History, " +
            "ED LDA Documentation table, ED Events table, Follow-up Information, " +
            "Discharge Instructions, Discharge References/Attachments, Communication Routing History, " +
            "ED Charges table (description, code, date, provider, modifiers, qty, status — extract ONLY what is written, do not assign codes), " +
            "providers (detect MD vs PA/NP by credential — ignore RN/LPN/support staff). " +
            "Do NOT assign ICD-10 or CPT codes — extract diagnosis and procedure descriptions as text only. Code assignment is done by a separate service. " +
            "Return structured JSON per the system prompt schema.";

    /**
     * E/M (MDM) reference labels — use when interpreting problem complexity, data,
     * and risk sections, and client-specific considerations (EKG/Ultrasound/X-ray).
     */
    public static final String EM_CODING_REFERENCE_CONTEXT = """
            EM CODING REFERENCE — map document content to these categories when extracting MDM / coding sections:

            PROBLEM COMPLEXITY (examples by tier):
            Self-Limited / Minor Problem(s)
            Stable Chronic Illness
            Stable Acute Illness
            Acute, Uncomplicated Illness or Injury
            ————————————————————————————————————————
            Chronic Illness w/ Exacerbation, Progression, or Side Effects of Treatment
            Acute, Complicated Injury
            Acute Illness w/ Systemic Symptoms
            Undiagnosed New Problem w/ Uncertain Prognosis
            ————————————————————————————————————————
            Chronic Illness(es) with Severe Exacerbation, Progression, or SE of Treatment
            Acute or Chronic Illness/Injury Posing Threat to Life or Bodily Function Suggestion
            DATA Suggested Level 4
            Bills Separate Interps?
            Please check this clients coding considerations for EKG/Ultrasound/X-ray

            Data N/A - Skipping data section as not needed for E/M
            No Data
            Review of External Notes Suggestion (1)

            Order and/or Review of Tests
            Rhythm Strip
            EKG

            All Other Rad (XR/CT/MR/NM) Suggestion (1)

            US Suggestion (1)

            Labs Suggestion (3)

            Independent Interpretation of Tests
            Rhythm Strip
            EKG

            All Other Rad (XR/CT/MR/NM)

            US

            Assessment Requiring Independent Historian
            Discussion of Management or Test Interpretation w/ External Provider
            RISK Suggested Level 5
            Minimal
            Rest, Gargle, Bandages, or Dressings
            Simple Localized Rash or Insect Bite (No Meds and No Fever)
            ————————————————————————————————————————
            OTC medications
            Minor Surgery w/ No Risk Factors
            Misc Procedures or Treatments in ED (e.g., enema, hair turnicate removal, earring removal)
            Specific Follow-up Instructions with External Provider Given
            Radiation exposure from extremity x-ray
            Throat or Nasal Swab for Diagnostic Testing
            ————————————————————————————————————————
            Prescription Drugs Suggestion
            IV Fluids w/ or w/o Additives
            Minor Surgery w/ Risk Factors
            Major Surgery w/ No Risk Factors
            Diagnosis or Treatment Significantly Limited by Social Determinants of Health
            Radiation exposure from any CT scan or x-ray of Head, Neck, or Torso
            Rigid Musculoskeletal Immobilization (e.g. splint or cast)
            Infant OTC Meds
            ————————————————————————————————————————
            Drug Therapy Requiring Monitoring for Toxicity
            Parenteral Controlled Substances
            Major Surgery w/ Risk Factors
            Emergency Major Surgery
            Decision regarding Hospitalization or Escalation of Hospital Care
            Decision not to Resuscitate or De-escalate Care Due to Poor Prognosis
            CT scan w/ IV contrast
            Category D Pregnancy Medications
            Administration of Moderate Sedation
            Physical restraints
            IV Anticoagulation Therapy
            """;

    private static final String SYSTEM_PROMPT_WITH_EM_REFERENCE =
            DEFAULT_SYSTEM_PROMPT + "\n\n" + EM_CODING_REFERENCE_CONTEXT;

    // ─── CONSTRUCTORS ────────────────────────────────────────────────────────

    public ClinicalExtractionService() {
	this(new LLMService());
    }

    public ClinicalExtractionService(LLMService llmService) {
	this.llmService = llmService;
    }

    // ─── TEXT → Map ──────────────────────────────────────────────────────────

    public Map<String, Object> extract(String clinicalText) throws Exception {
	return extract(DEFAULT_SYSTEM_PROMPT, clinicalText);
    }

    public Map<String, Object> extract(String systemPrompt, String userText) throws Exception {
	Map<String, Object> result = llmService.callToMap(systemPrompt, userText);
	saveResult(result);
	return result;
    }

    // ─── IMAGE FILE → Map ───────────────────────────────────────────────────

    public Map<String, Object> extractFromImage(File imageFile) throws Exception {
	return extractFromImage(imageFile, SYSTEM_PROMPT_WITH_EM_REFERENCE, DEFAULT_IMAGE_USER_PROMPT);
    }

    public Map<String, Object> extractFromImage(File imageFile, String systemPrompt, String userPrompt)
	    throws Exception {
	Map<String, Object> result = llmService.callWithImageToMap(imageFile, systemPrompt, userPrompt);
	saveResult(result);
	return result;
    }

    public Map<String, Object> extractFromImagePath(String imagePath) throws Exception {
	return extractFromImage(new File(imagePath));
    }

    public Map<String, Object> extractFromImagePath(String imagePath, String systemPrompt, String userPrompt)
	    throws Exception {
	return extractFromImage(new File(imagePath), systemPrompt, userPrompt);
    }

    // ─── BASE64 → Map ───────────────────────────────────────────────────────

    public Map<String, Object> extractFromBase64(String base64Image) throws Exception {
	return extractFromBase64(base64Image, "image/png");
    }

    public Map<String, Object> extractFromBase64(String base64Image, String mediaType) throws Exception {
	return extractFromBase64(base64Image, mediaType, SYSTEM_PROMPT_WITH_EM_REFERENCE, DEFAULT_IMAGE_USER_PROMPT);
    }

    public Map<String, Object> extractFromBase64(String base64Image, String mediaType, String systemPrompt,
	    String userPrompt) throws Exception {
	Map<String, Object> result = llmService.callWithBase64ToMap(base64Image, mediaType, systemPrompt, userPrompt);
	saveResult(result);
	return result;
    }

    // ─── TEXT → POJO ─────────────────────────────────────────────────────────

    public ClinicalExtractionResult extractAsObject(String clinicalText) throws Exception {
	Map<String, Object> map = extract(clinicalText);
	return gson.fromJson(gson.toJson(map), ClinicalExtractionResult.class);
    }

    public ClinicalExtractionResult extractAsObject(String systemPrompt, String userText) throws Exception {
	Map<String, Object> map = extract(systemPrompt, userText);
	return gson.fromJson(gson.toJson(map), ClinicalExtractionResult.class);
    }

    // ─── IMAGE FILE → POJO ──────────────────────────────────────────────────

    public ClinicalExtractionResult extractFromImageAsObject(File imageFile) throws Exception {
	Map<String, Object> map = extractFromImage(imageFile);
	return gson.fromJson(gson.toJson(map), ClinicalExtractionResult.class);
    }

    public ClinicalExtractionResult extractFromImageAsObject(File imageFile, String systemPrompt, String userPrompt)
	    throws Exception {
	Map<String, Object> map = extractFromImage(imageFile, systemPrompt, userPrompt);
	return gson.fromJson(gson.toJson(map), ClinicalExtractionResult.class);
    }

    // ─── BASE64 → POJO ──────────────────────────────────────────────────────

    public ClinicalExtractionResult extractFromBase64AsObject(String base64Image, String mediaType) throws Exception {
	Map<String, Object> map = extractFromBase64(base64Image, mediaType);
	return gson.fromJson(gson.toJson(map), ClinicalExtractionResult.class);
    }

    public ClinicalExtractionResult extractFromBase64AsObject(String base64Image, String mediaType, String systemPrompt,
	    String userPrompt) throws Exception {
	Map<String, Object> map = extractFromBase64(base64Image, mediaType, systemPrompt, userPrompt);
	return gson.fromJson(gson.toJson(map), ClinicalExtractionResult.class);
    }

    // ─── INTERNAL ────────────────────────────────────────────────────────────

    private void saveResult(Map<String, Object> resultMap) {
	try {
	    JsonFileUtil.saveToJsonFileAtPath(resultMap, "resources/jsonfolder/outputmap.json", true);
	} catch (Exception e) {
	    e.printStackTrace();
	}
    }
}
