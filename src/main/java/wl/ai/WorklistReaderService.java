package wl.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the medical authorization worklist table from a screenshot.
 * All API requests go through {@link LLMService}.
 * All methods return Map<String, Object>.
 */
public class WorklistReaderService {

    private static final Logger logger = LoggerFactory.getLogger(WorklistReaderService.class);

    private static final String SYSTEM_MSG =
            "You are a medical authorization worklist extraction assistant. "
            + "Extract exactly the data asked for. Return ONLY valid JSON — "
            + "no markdown fences, no explanation.";

    private static final String PROMPT =
            "This is a medical authorization worklist screen.\n\n"
            + "STEP 1 — Header bar (top of the screen):\n"
            + "Find text in the format  \"X of Y Selected\", \"X of Y Viewed\", \"X of Y Worked\".\n"
            + "Extract X (current) and Y (total) for each.\n"
            + "Example: \"0 of 334 Selected\" → selected_count=0, total_accounts=334.\n\n"
            + "STEP 2 — Table rows:\n"
            + "The table has these columns in order:\n"
            + "  1. Name (LAST,FIRST format)\n"
            + "  2. DOB (MM/DD/YYYY)\n"
            + "  3. Status\n"
            + "  4. Need By date\n"
            + "  5. Auth/Referral Type\n"
            + "  6. Ref Location\n"
            + "  7. Attending Provider\n"
            + "  8. Eff Date\n"
            + "  9. Units SCH\n"
            + " 10. Units REM\n"
            + " 11. Exp Date\n"
            + " 12. Insurance\n"
            + " 13. Financial Class\n\n"
            + "Return ONLY this JSON — no markdown:\n"
            + "{\n"
            + "  \"total_accounts\": 334,\n"
            + "  \"selected_count\": 0,\n"
            + "  \"viewed_count\": 0,\n"
            + "  \"worked_count\": 0,\n"
            + "  \"accounts\": [\n"
            + "    {\n"
            + "      \"name\": \"WARD,TRACY L\",\n"
            + "      \"dob\": \"04/25/1972\",\n"
            + "      \"status\": \"Expired\",\n"
            + "      \"need_by\": \"\",\n"
            + "      \"auth_referral_type\": \"Outpatient Sleep Center\",\n"
            + "      \"ref_location\": \"WMC Sleep Center\",\n"
            + "      \"attending_provider\": \"Smith,Irving S\",\n"
            + "      \"eff_date\": \"01/27/26\",\n"
            + "      \"units_sch\": \"0\",\n"
            + "      \"units_rem\": \"1\",\n"
            + "      \"exp_date\": \"01/27/26\",\n"
            + "      \"insurance\": \"WELL SENSE HEALTH PLAN\",\n"
            + "      \"financial_class\": \"MEDICAID\"\n"
            + "    }\n"
            + "  ]\n"
            + "}";

    private final LLMService llmService;

    public WorklistReaderService() {
        this.llmService = new LLMService();
    }

    public WorklistReaderService(LLMService llmService) {
        this.llmService = llmService;
    }

    public WorklistReaderService(String apiUrl) {
        this.llmService = new LLMService(apiUrl);
    }

    /** Read the full worklist → Map. */
    public Map<String, Object> readWorklist(String imagePath) throws IOException {
        return readWorklist(new File(imagePath));
    }

    /** Read the full worklist → Map. */
    public Map<String, Object> readWorklist(File imageFile) throws IOException {
        logger.info("Reading worklist from: {}", imageFile.getName());
        Map<String, Object> result = llmService.callWithImageToMap(imageFile, SYSTEM_MSG, PROMPT, 4096, 0.0);
        logger.info("Worklist read: total_accounts={}, rows={}",
                result.get("total_accounts"),
                result.get("accounts") instanceof List ? ((List<?>) result.get("accounts")).size() : 0);
        return result;
    }

    /** Returns only the total account count. */
    public Map<String, Object> getTotalAccountCount(String imagePath) throws IOException {
        Map<String, Object> result = readWorklist(imagePath);
        Map<String, Object> countMap = new HashMap<>();
        countMap.put("total_accounts", result.getOrDefault("total_accounts", 0));
        return countMap;
    }

    /** Returns only the list of account rows as a Map. */
    public Map<String, Object> getAccounts(String imagePath) throws IOException {
        Map<String, Object> result = readWorklist(imagePath);
        Map<String, Object> accountsMap = new HashMap<>();
        Object accounts = result.get("accounts");
        accountsMap.put("accounts", accounts instanceof List ? accounts : new ArrayList<>());
        return accountsMap;
    }

    /** Returns a summary Map. */
    public Map<String, Object> getSummary(String imagePath) throws IOException {
        Map<String, Object> result = readWorklist(imagePath);
        Map<String, Object> summary = new HashMap<>();
        summary.put("total_accounts", result.getOrDefault("total_accounts", 0));
        summary.put("selected_count", result.getOrDefault("selected_count", 0));
        summary.put("viewed_count", result.getOrDefault("viewed_count", 0));
        summary.put("worked_count", result.getOrDefault("worked_count", 0));
        Object accounts = result.get("accounts");
        summary.put("visible_rows", accounts instanceof List ? ((List<?>) accounts).size() : 0);
        return summary;
    }
}
