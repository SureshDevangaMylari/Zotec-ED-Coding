package wl.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Image-to-JSON extraction using the self-hosted Qwen VL model.
 * All API requests go through {@link LLMService}.
 * All methods return Map<String, Object>.
 */
public class LLMServiceImageToJson {
    private static final Logger logger = LoggerFactory.getLogger(LLMServiceImageToJson.class);
    private final LLMService llmService;

    private static final String SYSTEM_MESSAGE = "You are a document extraction assistant. Return ONLY valid JSON.";

    public LLMServiceImageToJson(String apiUrl) {
        this.llmService = (apiUrl != null && !apiUrl.isEmpty()) ? new LLMService(apiUrl) : new LLMService();
    }

    public LLMServiceImageToJson(LLMService llmService) {
        this.llmService = llmService;
    }

    public Map<String, Object> readImageSmartJson(String imagePath) {
        try {
            logger.info("Reading image: {}", imagePath);

            if (!Files.exists(Paths.get(imagePath))) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Image file not found: " + imagePath);
                error.put("content_type", "unknown");
                return error;
            }

            String prompt = buildSmartJsonPrompt();
            return llmService.callWithImageToMap(new File(imagePath), SYSTEM_MESSAGE, prompt, 8192, 0.1);

        } catch (Exception e) {
            logger.error("Error reading image smart JSON: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("content_type", "unknown");
            error.put("error", e.getMessage());
            return error;
        }
    }

    public Map<String, Object> readTextFromImage(String imagePath, boolean extractStructured, boolean includeCoordinates) {
        try {
            String prompt = extractStructured ? buildStructuredTextPrompt() : buildPlainTextPrompt();
            return llmService.callWithImageToMap(new File(imagePath), SYSTEM_MESSAGE, prompt, 4096, 0.1);
        } catch (Exception e) {
            logger.error("Error reading text from image: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return error;
        }
    }

    public Map<String, Object> readTextStructured(String imagePath) {
        return readTextFromImage(imagePath, true, true);
    }

    public Map<String, Object> findTextInImage(String searchText, String imagePath) {
        try {
            String prompt = buildFindTextPrompt(searchText);
            return llmService.callWithImageToMap(new File(imagePath), SYSTEM_MESSAGE, prompt, 2048, 0.1);
        } catch (Exception e) {
            logger.error("Error finding text in image: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return error;
        }
    }

    public Map<String, Object> extractTableData(String imagePath) {
        try {
            String prompt = buildTableExtractionPrompt();
            return llmService.callWithImageToMap(new File(imagePath), SYSTEM_MESSAGE, prompt, 4096, 0.1);
        } catch (Exception e) {
            logger.error("Error extracting table data: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return error;
        }
    }

    public Map<String, Object> readImageToJson(String imagePath) {
        Map<String, Object> result = new HashMap<>();

        if (!Files.exists(Paths.get(imagePath))) {
            result.put("error", "Image file not found: " + imagePath);
            return result;
        }

        try {
            Map<String, Object> textResult = readTextFromImage(imagePath, false, false);
            result.put("text", textResult);

            Map<String, Object> structured = readTextStructured(imagePath);
            result.put("text_elements", structured);

            Map<String, Object> tables = extractTableData(imagePath);
            result.put("tables", tables);

        } catch (Exception e) {
            result.put("error", e.getMessage());
        }

        return result;
    }

    public Map<String, Object> extractClaimsTable(String imagePath) {
        Map<String, Object> result = new HashMap<>();
        result.put("headers", new ArrayList<>());
        result.put("claims", new ArrayList<>());
        result.put("summary", new HashMap<>());

        if (!Files.exists(Paths.get(imagePath))) {
            result.put("error", "Image file not found: " + imagePath);
            return result;
        }

        try {
            String prompt = buildClaimsTablePrompt();
            return llmService.callWithImageToMap(new File(imagePath), SYSTEM_MESSAGE, prompt, 8192, 0.1);
        } catch (Exception e) {
            logger.error("Error extracting claims table: {}", e.getMessage());
            result.put("error", e.getMessage());
        }

        return result;
    }

    public static String buildSmartJsonPromptStatic() {
        return new LLMServiceImageToJson((String) null).buildSmartJsonPrompt();
    }

    // ─── PROMPTS ──────────────────────────────────────────────────────────────

    String buildSmartJsonPrompt() {
        return "Analyze this image and extract all content in structured JSON format.\n"
                + "First, identify the content type:\n\n"
                + "1. TABLE - if the image contains a data table with rows and columns\n"
                + "2. FORM - if the image contains form fields, labels, and input areas\n"
                + "3. DOCUMENT - if the image contains structured document with sections\n"
                + "4. TEXT - if the image contains mostly plain text without structure (including notepad, text editor, plain text areas)\n"
                + "5. NOTEPAD - if the image shows a notepad, text editor, or simple text area with editable text content\n\n"
                + "Then, extract the content based on the identified type:\n\n"
                + "FOR NOTEPAD/TEXT EDITOR:\n\n"
                + "CRITICAL: If this image contains a NOTEPAD or TEXT EDITOR window, you MUST:\n"
                + "1. Identify the notepad/text editor area\n"
                + "2. Extract ALL text content from INSIDE the notepad/text editor area ONLY\n"
                + "3. Ignore UI elements outside the notepad\n"
                + "4. Focus on the actual text content within the editable text area\n\n"
                + "Return JSON:\n\n"
                + "{\n"
                + "    \"content_type\": \"text\",\n"
                + "    \"text\": \"EXACT text content from the notepad/text editor area ONLY.\",\n"
                + "    \"text_elements\": [\"line1\", \"line2\", \"line3\", ...],\n"
                + "    \"notepad_content\": \"complete text content from notepad area\",\n"
                + "    \"billing_notes\": \"if billing notes present, extract here\"\n"
                + "}\n\n"
                + "FOR TABLES:\n\n"
                + "Return JSON:\n\n"
                + "{\n"
                + "    \"content_type\": \"table\",\n"
                + "    \"headers\": [\"header1\", \"header2\", ...],\n"
                + "    \"rows\": [{\"header1\": \"value1\", \"header2\": \"value2\", ...}, ...],\n"
                + "    \"summary\": {\"any_summary_info\": \"value\"},\n"
                + "    \"billing_notes\": \"extract any billing notes if present\"\n"
                + "}\n\n"
                + "FOR FORMS:\n\n"
                + "Return JSON:\n\n"
                + "{\n"
                + "    \"content_type\": \"form\",\n"
                + "    \"fields\": [{\"label\": \"Field Label\", \"value\": \"Field Value\", \"type\": \"text/checkbox/select\"}, ...],\n"
                + "    \"text\": \"any additional text content\",\n"
                + "    \"billing_notes\": \"extract any billing notes if present\"\n"
                + "}\n\n"
                + "FOR DOCUMENTS:\n\n"
                + "Return JSON with section names as direct top-level keys in camelCase.\n\n"
                + "FOR PLAIN TEXT:\n\n"
                + "Return JSON:\n\n"
                + "{\n"
                + "    \"content_type\": \"text\",\n"
                + "    \"text\": \"ALL extracted text from the image\",\n"
                + "    \"text_elements\": [\"line1\", \"line2\", ...],\n"
                + "    \"billing_notes\": \"extract any billing notes if present\"\n"
                + "}\n\n"
                + "Rules:\n"
                + "- Accurately identify the content type\n"
                + "- Extract ALL visible data\n"
                + "- Character accuracy is CRITICAL\n"
                + "- ALWAYS include billing_notes field\n"
                + "- Return clean, valid JSON only\n\n"
                + "Return ONLY valid JSON, no markdown, no explanations.";
    }

    private String buildPlainTextPrompt() {
        return "Extract all visible text from this image.\n"
                + "Return ONLY the extracted text, no explanations, no markdown formatting.";
    }

    private String buildStructuredTextPrompt() {
        return "Extract all visible text from this image and return as structured JSON.\n"
                + "Return a JSON array: [{\"text\": \"...\", \"x\": 0, \"y\": 0, \"width\": 0, \"height\": 0, \"confidence\": 1.0}]\n"
                + "Return ONLY valid JSON, no markdown, no explanations.";
    }

    private String buildFindTextPrompt(String searchText) {
        return String.format(
                "Find the text \"%s\" in this image and return its location.\n"
                + "Return JSON array: [{\"text\": \"...\", \"x\": 0, \"y\": 0, \"width\": 0, \"height\": 0, \"confidence\": 1.0, \"context\": \"...\"}]\n"
                + "If text not found, return []. Return ONLY valid JSON.", searchText);
    }

    private String buildTableExtractionPrompt() {
        return "Extract all data from tables in this image.\n"
                + "Return: {\"tables\": [{\"headers\": [\"h1\",...], \"rows\": [[\"c1\",...], ...]}]}\n"
                + "Return ONLY valid JSON, no markdown, no explanations.";
    }

    private String buildClaimsTablePrompt() {
        return "Extract all data from this claims table image.\n"
                + "Return: {\"headers\": [...], \"claims\": [{\"claim_number\": \"...\", \"service_date\": \"...\", ...}], \"summary\": {...}}\n"
                + "Return ONLY valid JSON, no markdown formatting, no explanations.";
    }

    // ─── STATIC HELPERS ───────────────────────────────────────────────────────

    public static Map<String, Object> readImageTextJson(String imagePath, String apiUrl) {
        try {
            LLMServiceImageToJson reader = new LLMServiceImageToJson(apiUrl);
            return reader.readImageToJson(imagePath);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("text", "");
            error.put("structured", new ArrayList<>());
            error.put("tables", new ArrayList<>());
            return error;
        }
    }

    public static Map<String, Object> extractClaimsTableJson(String imagePath, String apiUrl) {
        try {
            LLMServiceImageToJson reader = new LLMServiceImageToJson(apiUrl);
            return reader.extractClaimsTable(imagePath);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("headers", new ArrayList<>());
            error.put("claims", new ArrayList<>());
            error.put("summary", new HashMap<>());
            return error;
        }
    }

    public static Map<String, Object> readImageSmartJson(String imagePath, String apiUrl) {
        logger.info("reading .....");
        try {
            LLMServiceImageToJson reader = new LLMServiceImageToJson(apiUrl);
            return reader.readImageSmartJson(imagePath);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("content_type", "unknown");
            error.put("error", e.getMessage());
            return error;
        }
    }
}
