package wl.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.*;

/**
 * Analyzes HTML DOM via Qwen model and executes dynamic Playwright steps.
 * <p>
 * Flow: HTML DOM → Qwen (returns JSON steps with XPath) → Execute on Page.
 * <p>
 * Usage:
 * <pre>
 *   DynamicFormExecutionService svc = new DynamicFormExecutionService(page);
 *   svc.executeFromHtml(htmlDom, dataToFill);
 *   // or just steps from HTML:
 *   svc.executeFromHtml(htmlDom);
 * </pre>
 */
public class DynamicFormExecutionService {

    private static final Logger log = LogManager.getLogger(DynamicFormExecutionService.class);
    private static final String XPATH_PREFIX = "xpath=";

    private final Page page;
    private final LLMService llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public DynamicFormExecutionService(Page page) {
        this(page, new LLMService());
    }

    public DynamicFormExecutionService(Page page, LLMService llmService) {
        this.page = page;
        this.llm = llmService != null ? llmService : new LLMService();
    }

    /**
     * Send HTML DOM to Qwen, get execution steps, and run them on the page.
     *
     * @param htmlDom      The HTML DOM string (e.g. from page.content() or a snippet)
     * @param dataToFill   Optional map of field names/labels to values (e.g. {"Patient": "John", "MRN": "123"})
     * @return Number of steps successfully executed
     */
    public int executeFromHtml(String htmlDom, Map<String, String> dataToFill) throws IOException {
        List<ExecutionStep> steps = getStepsFromHtml(htmlDom, dataToFill);
        return executeSteps(steps);
    }

    /**
     * Same as above but without pre-filled data (LLM decides steps from HTML alone).
     */
    public int executeFromHtml(String htmlDom) throws IOException {
        return executeFromHtml(htmlDom, null);
    }

    /**
     * Call Qwen to analyze HTML and return execution steps as JSON.
     */
    public List<ExecutionStep> getStepsFromHtml(String htmlDom, Map<String, String> dataToFill) throws IOException {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(htmlDom, dataToFill);
        String raw = llm.call(systemPrompt, userPrompt, 4096, 0.1);
        return parseSteps(raw);
    }

    /**
     * Execute a list of steps on the page.
     */
    public int executeSteps(List<ExecutionStep> steps) {
        if (steps == null || steps.isEmpty()) {
            log.warn("No steps to execute");
            return 0;
        }
        int executed = 0;
        for (int i = 0; i < steps.size(); i++) {
            ExecutionStep step = steps.get(i);
            try {
                executeStep(step);
                executed++;
                log.info("[{}/{}] {} - {}", i + 1, steps.size(), step.getAction(), step.getDescription());
            } catch (Exception e) {
                log.warn("Step {} failed: {} - {}", i + 1, step.getAction(), e.getMessage());
            }
        }
        return executed;
    }

    private void executeStep(ExecutionStep step) {
        String action = step.getAction();
        String xpath = step.getXpath();
        if (xpath == null || xpath.isBlank()) return;

        String selector = xpath.startsWith("//") || xpath.startsWith("(") ? XPATH_PREFIX + xpath : xpath;
        Locator loc = page.locator(selector).first();

        switch (action == null ? "" : action.toLowerCase()) {
            case "click" -> {
                loc.waitFor(new Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE).setTimeout(10000));
                loc.click(new Locator.ClickOptions().setForce(true));
                sleep(step.getWaitMs() > 0 ? step.getWaitMs() : 300);
            }
            case "fill", "type" -> {
                String value = step.getValue();
                if (value == null) value = "";
                loc.waitFor(new Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE).setTimeout(10000));
                loc.fill(value);
                sleep(step.getWaitMs() > 0 ? step.getWaitMs() : 200);
            }
            case "press" -> {
                String key = step.getValue();
                if (key == null) key = "Enter";
                loc.press(key);
                sleep(step.getWaitMs() > 0 ? step.getWaitMs() : 200);
            }
            case "select" -> {
                String option = step.getValue();
                if (option != null) loc.selectOption(option);
                sleep(step.getWaitMs() > 0 ? step.getWaitMs() : 200);
            }
            case "wait" -> sleep(step.getWaitMs() > 0 ? step.getWaitMs() : 1000);
            default -> log.warn("Unknown action: {}", action);
        }
    }

    private String buildSystemPrompt() {
        return """
            You are an expert at analyzing HTML forms and generating Playwright execution steps.
            Given HTML DOM and optional data, output a JSON array of steps.

            Each step MUST have:
            - "action": "click" | "fill" | "type" | "press" | "select" | "wait"
            - "xpath": valid XPath selector (e.g. //*[text()='Patient']/following-sibling::*//input, //button[text()='Submit'])
            - "value": (required for fill/type/press/select) the value to use
            - "description": short human-readable description
            - "waitMs": (optional) milliseconds to wait after this step

            RULES:
            - Use XPath that matches by visible text, labels, or stable attributes. Avoid auto-generated ids.
            - For select2/dropdowns: click the trigger first, wait, then fill the search input, then click first result.
            - Prefer //*[text()='Label']/following-sibling::*//input or similar for form fields.
            - Output ONLY the JSON array, no markdown, no explanation.
            """;
    }

    private String buildUserPrompt(String htmlDom, Map<String, String> dataToFill) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze this HTML and produce execution steps to fill the form.\n\n");
        sb.append("HTML DOM (truncated if long):\n");
        String truncated = htmlDom.length() > 30000 ? htmlDom.substring(0, 30000) + "\n... [truncated]" : htmlDom;
        sb.append(truncated);
        if (dataToFill != null && !dataToFill.isEmpty()) {
            sb.append("\n\nData to fill:\n");
            dataToFill.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append("\n"));
        }
        sb.append("\n\nReturn JSON array of steps only:");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<ExecutionStep> parseSteps(String raw) {
        String json = extractJsonArray(raw);
        try {
            List<Map<String, Object>> list = mapper.readValue(json, new TypeReference<>() {});
            List<ExecutionStep> steps = new ArrayList<>();
            for (Map<String, Object> m : list) {
                ExecutionStep s = new ExecutionStep();
                s.setAction((String) m.get("action"));
                s.setXpath((String) m.get("xpath"));
                Object v = m.get("value");
                s.setValue(v != null ? String.valueOf(v) : null);
                s.setDescription((String) m.get("description"));
                Object w = m.get("waitMs");
                s.setWaitMs(w instanceof Number ? ((Number) w).intValue() : 0);
                steps.add(s);
            }
            return steps;
        } catch (Exception e) {
            log.warn("Failed to parse steps: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJsonArray(String text) {
        if (text == null) return "[]";
        String t = text.trim().replaceAll("(?s)```(?:json)?\\s*", "").trim();
        int start = t.indexOf('[');
        if (start < 0) return "[]";
        int depth = 0;
        int end = -1;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
        }
        if (end > start) return t.substring(start, end);
        return "[]";
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One execution step returned by the LLM.
     */
    public static class ExecutionStep {
        private String action;
        private String xpath;
        private String value;
        private String description;
        private int waitMs;

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getXpath() { return xpath; }
        public void setXpath(String xpath) { this.xpath = xpath; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getWaitMs() { return waitMs; }
        public void setWaitMs(int waitMs) { this.waitMs = waitMs; }
    }
}
