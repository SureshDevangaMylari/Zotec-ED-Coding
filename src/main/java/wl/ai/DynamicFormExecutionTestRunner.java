package wl.ai;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.wl.util.JsonReadService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Test runner for DynamicFormExecutionService.
 * <p>
 * Prerequisites:
 * - Chrome running with remote debugging: chrome.exe --remote-debugging-port=9222
 * - Coding Workfile tab open in browser
 * - LLM (Qwen) API available at default URL
 * <p>
 * Run: main() or mvn exec:java -Dexec.mainClass="wl.ai.DynamicFormExecutionTestRunner"
 */
public class DynamicFormExecutionTestRunner {

    private static final Logger log = LogManager.getLogger(DynamicFormExecutionTestRunner.class);
    private static final String CDP_URL = "http://localhost:9222";

    public static void main(String[] args) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().connectOverCDP(CDP_URL);
            BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);

            Page page = findCodingWorkfilePage(context);
            if (page == null) {
                page = context.pages().get(0);
                log.warn("Coding Workfile tab not found, using first tab");
            } else {
                log.info("Found Coding Workfile tab");
            }

            runDynamicFormExecution(page);
        } catch (Exception e) {
            log.error("Test failed", e);
            e.printStackTrace();
        }
    }

    /**
     * Core test: get HTML from page, build data from output.json, execute via Qwen.
     */
    public static void runDynamicFormExecution(Page page) throws Exception {
        DynamicFormExecutionService svc = new DynamicFormExecutionService(page);

        // 1. Get HTML from the page (form area or full page)
        String html = getFormHtml(page);
        log.info("HTML length: {} chars", html.length());

        // 2. Build data to fill from output.json (or use sample)
        Map<String, String> dataToFill = buildDataFromOutputJson();
        log.info("Data to fill: {}", dataToFill);

        // 3. Execute: HTML + data → Qwen → steps → Playwright
        int executed = svc.executeFromHtml(html, dataToFill);
        log.info("Executed {} steps", executed);
    }

    /**
     * Run with HTML from file (e.g. html.htm) - no browser needed for step generation.
     */
    public static void runWithHtmlFile(String htmlFilePath) throws Exception {
        String html = java.nio.file.Files.readString(java.nio.file.Path.of(htmlFilePath));
        Map<String, String> data = buildDataFromOutputJson();
        log.info("Loaded HTML from {} ({} chars)", htmlFilePath, html.length());
        log.info("Data: {}", data);

        // We need a page to execute - this method only gets steps, doesn't execute
        // Use getStepsFromHtml if you want steps without execution
        log.info("For execution, pass a live Page. Use runDynamicFormExecution(page) with browser connected.");
    }

    private static Page findCodingWorkfilePage(BrowserContext context) {
        for (Page p : context.pages()) {
            if (p.title().contains("Coding Workfile")) return p;
        }
        return null;
    }

    private static String getFormHtml(Page page) {
        try {
            // Try form first, fallback to full page
            var form = page.locator("form").first();
            if (form.count() > 0) {
                return form.innerHTML();
            }
        } catch (Exception e) {
            log.debug("Form not found, using full page: {}", e.getMessage());
        }
        return page.content();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> buildDataFromOutputJson() {
        Map<String, String> flat = new HashMap<>();
        try {
            JsonReadService reader = new JsonReadService();
            Map<String, Object> data = reader.readOutputJson();
            if (data == null || data.isEmpty()) {
                return getSampleData();
            }

            // patient.name
            Object patient = data.get("patient");
            if (patient instanceof Map) {
                String name = (String) ((Map<?, ?>) patient).get("name");
                if (name != null) flat.put("Patient", name);
                String mrn = (String) ((Map<?, ?>) patient).get("mrn");
                if (mrn != null) flat.put("MRN", mrn);
            }

            // identifiers
            Object identifiers = data.get("identifiers");
            if (identifiers instanceof Map) {
                Map<?, ?> id = (Map<?, ?>) identifiers;
                String mrn = (String) id.get("mrn");
                if (mrn != null) flat.put("MRN", mrn);
                String acct = (String) id.get("account_number");
                if (acct != null) flat.put("Encounter #", acct);
            }

            // batch_info.batch_text → Service Location
            Object batch = data.get("batch_info");
            if (batch instanceof Map) {
                String batchText = (String) ((Map<?, ?>) batch).get("batch_text");
                if (batchText != null) flat.put("Service Location", batchText);
            }

            if (flat.isEmpty()) return getSampleData();
        } catch (Exception e) {
            log.warn("Could not read output.json: {}", e.getMessage());
            return getSampleData();
        }
        return flat;
    }

    private static Map<String, String> getSampleData() {
        return Map.of(
                "Patient", "Baehr, Tyler Michael",
                "MRN", "23618467",
                "Encounter #", "8208695476",
                "Service Location", "Carson Tahoe RMC Reports"
        );
    }
}
