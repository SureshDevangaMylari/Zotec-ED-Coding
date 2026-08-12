package wl.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Eligibility Reader - Extracts eligibility information from insurance
 * eligibility verification screens.
 * All API requests go through {@link LLMService}.
 * All methods return Map<String, Object>.
 */
public class EligibilityReader {
    private static final Logger logger = LoggerFactory.getLogger(EligibilityReader.class);
    private final LLMService llmService;

    private static final String SYSTEM_MSG =
            "You are an insurance eligibility extraction assistant. Return ONLY valid JSON — no markdown, no explanation.";

    public EligibilityReader() {
        this.llmService = new LLMService();
    }

    public EligibilityReader(String apiKey) {
        this.llmService = new LLMService();
    }

    public EligibilityReader(LLMService llmService) {
        this.llmService = llmService;
    }

    public Map<String, Object> readEligibility(String imagePath) {
        try {
            logger.info("Reading eligibility from image: {}", imagePath);

            if (!Files.exists(Paths.get(imagePath))) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Image file not found: " + imagePath);
                error.put("status", "error");
                return error;
            }

            String prompt = buildEligibilityPrompt();
            return llmService.callWithImageToMap(new File(imagePath), SYSTEM_MSG, prompt, 8192, 0.1);

        } catch (Exception e) {
            logger.error("Error reading eligibility: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return error;
        }
    }

    private String buildEligibilityPrompt() {
        return "Analyze this insurance eligibility verification screen and extract ONLY the complete header/title text exactly as it appears.\n\n"
                + "Look for the main header or title at the top of the screen that indicates eligibility status.\n\n"
                + "Return JSON with this EXACT structure:\n\n"
                + "{\n"
                + "    \"header\": \"Complete header text exactly as shown in the image\"\n"
                + "}\n\n"
                + "EXTRACTION RULES:\n"
                + "1. Focus ONLY on the header/title area at the top of the screen\n"
                + "2. Extract the COMPLETE header text exactly as it appears\n"
                + "3. Examples:\n"
                + "   - \"Member is Eligible\" → return exactly \"Member is Eligible\"\n"
                + "   - \"Member is Not Eligible\" → return exactly \"Member is Not Eligible\"\n"
                + "4. DO NOT extract any other information\n"
                + "5. Preserve the exact text as shown\n\n"
                + "Return ONLY valid JSON, no markdown, no explanations.";
    }

    public static Map<String, Object> readEligibilityFromImage(String imagePath) {
        try {
            EligibilityReader reader = new EligibilityReader();
            return reader.readEligibility(imagePath);
        } catch (Exception e) {
            logger.error("Error in static readEligibilityFromImage: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return error;
        }
    }

    public static Map<String, Object> readEligibilityFromImage(String imagePath, String apiKey) {
        return readEligibilityFromImage(imagePath);
    }
}
