package wl.ai;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.Map;

/**
 * Example usage of DynamicFormExecutionService.
 * <p>
 * 1. Get HTML from your page: String html = page.content(); 2. Optionally
 * provide data to fill: Map.of("Patient", "John", "MRN", "123") 3. Execute:
 * service.executeFromHtml(html, data);
 */
public class DynamicFormExecutionExample {

    public static void runWithHtmlFromPage(Page page) throws Exception {
	DynamicFormExecutionService svc = new DynamicFormExecutionService(page);

	// Option A: Get full page HTMLsysout
	Locator lo = page.locator("//div");
	String html = lo.innerText();

	// Option B: Get a specific section (e.g. form only)
	// String html = page.locator("form").first().innerHTML();

	Map<String, String> data = Map.of("Patient", "Baehr, Tyler Michael", "MRN", "23618467", "Service Location",
		"Carson Tahoe RMC Reports");

	int executed = svc.executeFromHtml(html, data);
	System.out.println("Executed " + executed + " steps");
    }

    public static void runWithPredefinedSteps(Page page) throws Exception {
	DynamicFormExecutionService svc = new DynamicFormExecutionService(page);

	// Get steps from LLM (e.g. from saved HTML file)
	String sampleHtml = "<form><label>Patient</label><input name='patient'/></form>";
	var steps = svc.getStepsFromHtml(sampleHtml, Map.of("Patient", "John Doe"));

	// Execute when ready
	int executed = svc.executeSteps(steps);
	System.out.println("Executed " + executed + " steps");
    }
}
