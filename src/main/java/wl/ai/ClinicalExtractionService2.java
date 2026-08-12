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
public class ClinicalExtractionService2 {

    private final LLMService llmService;
    private final Gson gson = new Gson();

    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are an expert medical coder and clinical document parser. Extract data from the provided clinical text or image to populate a Zotec coding workfile form. Return ONLY a single valid JSON object (no explanations, no markdown fences).

            EXTRACTION RULES:
            - Output must be valid JSON only — no prose, no markdown.
            - Preserve dates in M/d/yyyy format (e.g. 2/19/2026). If the source uses another format, convert it.
            - Use null for any field that is missing, empty, or cannot be determined.
            - Do NOT invent or guess values — use null instead.

            PROVIDER ROLE DETECTION (critical):
            - Credential classification:
                * "md" = MD, DO, M.D., D.O. (physicians only)
                * "pa" = PA, PA-C, PAC, NP, N.P., APP, APRN (mid-level providers only)
                * IGNORE these roles entirely — do NOT classify as md or pa: RN, LPN, CNA, RT, MA, EMT, Paramedic, Tech, Secretary, Unit Clerk. These are support staff, not billing providers.
            - In providers.all_providers, only include providers with role "md" or "pa". Do not include RN, LPN, or other support staff.
            - Provider placement rules (follow strictly):
                * If both an MD and a PA/NP are found:
                    → providers.supervising = the MD
                    → providers.rendering = the PA/NP
                * If ONLY an MD is found (no PA/NP at all):
                    → providers.rendering = the MD
                    → providers.supervising = null (all fields null — do NOT copy MD here)
                * If ONLY a PA/NP is found (no MD):
                    → providers.rendering = the PA/NP
                    → providers.supervising = null

            ENCOUNTER STATUS:
            - Set encounter.status to one of: "admitted", "discharged", "transferred", "left_ama", "expired", or null.
            - If the patient is in ED and NOT discharged/transferred/left/expired, set encounter.admitted to true.
            - If the patient is discharged or has a discharge disposition, set encounter.admitted to false.

            ED DISPOSITION (critical for admit logic):
            - Look for a section labeled "ED Disposition" in the document/image.
            - It is typically a small table with columns like "ED Disposition", "Condition", "Comment".
            - Extract the disposition value (e.g. "Expired", "Discharged", "Admitted", "Left AMA", "Transfer", etc.).
            - Put it into ed_disposition.status (exactly as written in the document, e.g. "Expired").
            - Also capture ed_disposition.condition and ed_disposition.comment if present (often "--" means null).
            - If ED Disposition status is "Discharged", "Expired", "Left AMA", "Transfer", or similar → encounter.admitted must be false.
            - If ED Disposition status is "Admitted" → encounter.admitted must be true.
            - If no ED Disposition section exists, set ed_disposition to null.

            DIAGNOSIS / CLINICAL IMPRESSION:
            - Map clinical impression, assessment, and diagnosis text to ICD-10 codes where possible.
            - Put each ICD-10 code string in billing.icd_codes array (e.g. ["I46.9", "K02.9"]).
            - Also populate diagnoses array with objects containing code, description, and type.

            PROCEDURE CODES:
            - Extract any CPT/HCPCS procedure codes mentioned.
            - Include modifier codes if present (e.g. 25, 59, 76).

            OUTPUT JSON (use this exact structure, fill values or null/empty arrays):
            {
              "identifiers": {
                "mrn": null,
                "account_number": null,
                "encounter_number": null,
                "patient_id": null
              },
              "patient": {
                "name": null,
                "dob": null,
                "age": null,
                "sex": null
              },
              "encounter": {
                "date_of_service": null,
                "admit_date": null,
                "encounter_date_time": null,
                "encounter_location": null,
                "service": null,
                "status": null,
                "admitted": null
              },
              "ed_disposition": {
                "status": null,
                "condition": null,
                "comment": null
              },
              "coding": {
                "profile": null,
                "service_location": null,
                "place_of_service": null,
                "department": null,
                "authorization_number": null
              },
              "providers": {
                "supervising": { "name": null, "npi": null, "credential": null },
                "rendering":   { "name": null, "npi": null, "credential": null },
                "physician_assistant": { "name": null, "npi": null, "credential": null },
                "all_providers": [
                  { "name": null, "role": "md|pa", "npi": null, "credential": null, "timestamp": null }
                ]
              },
              "billing": {
                "payor": null,
                "icd_codes": [],
                "accident_code": null,
                "accident_date": null
              },
              "procedures": [
                { "cpt_code": null, "description": null, "modifiers": [], "units": 1, "diagnoses_pointer": [] }
              ],
              "diagnoses": [
                { "code": null, "description": null, "type": "ICD10" }
              ],
              "visit": { "chief_complaint": null, "hpi": null },
              "vitals": {
                "bp_systolic": null, "bp_diastolic": null, "pulse": null,
                "respiration": null, "temp": null, "spo2": null,
                "height": null, "weight": null, "bmi": null
              },
              "physical_exam": null,
              "impression": null,
              "medications": [],
              "imaging": [ { "study": null, "result": null } ],
              "follow_up": null,
              "notes": { "unparsed_text": null }
            }
            """;

    public static final String DEFAULT_IMAGE_USER_PROMPT =
            "Extract all clinical data from this image for a Zotec coding workfile. " +
            "Focus on: patient name, DOB, MRN, account/encounter number, date of service, " +
            "providers (identify MD vs PA/NP by credential suffix in their name), " +
            "diagnoses with ICD-10 codes, procedure/CPT codes with modifiers, " +
            "admit status (admitted or discharged), service location, " +
            "and ED Disposition table (extract status like Expired/Discharged/Admitted, condition, comment). " +
            "Return structured JSON per the system prompt schema.";

    private static final String SYSTEM_PROMPT_WITH_EM_REFERENCE =
            DEFAULT_SYSTEM_PROMPT + "\n\n" + ClinicalExtractionService.EM_CODING_REFERENCE_CONTEXT;

    // ─── CONSTRUCTORS ────────────────────────────────────────────────────────

    public ClinicalExtractionService2() {
	this(new LLMService());
    }

    public ClinicalExtractionService2(LLMService llmService) {
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
