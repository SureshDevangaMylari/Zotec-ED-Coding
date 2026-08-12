package com.wl.zotecAgent.selection;

import com.microsoft.playwright.Page;
import com.wl.zotecAgent.edem.ED_EMExcelReader;
import com.wl.util.JsonReadService;
import wl.ai.LLMService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Automated selection flow: read output.json + Excel -> call Qwen LLM -> LLM analyzes -> best result.
 * No manual reading or analysis. Saves filled form and output with patient name.
 * <p>
 * Usage: run main() or call {@link #run(Map, String)} for file-based HTML, or {@link #runOnPage(Page, Map, String)}
 * to apply selections on the live Playwright page (no dependency on saved autoCoderForm.html).
 */
public class SelectionTestRunner {

    private static final String DEFAULT_EXCEL_PATH = "resources/ED_EM Supplemental Tool.xlsx";
    private static final String FORM_TEMPLATE = "resources/output/autoCoderForm.html";
    private static final String OUTPUT_DIR = "resources/output";
    private static final String OUTPUT_JSON_PATH = "resources/jsonfolder/outputSelecttion.json";

    /**
     * Runs the selection test with output.json and default Excel path.
     */
    public static void main(String[] args) {
	
	
	 
	JsonReadService reader = new JsonReadService();
	Map<String, Object> data = reader.readOutputJson();

	String excelPath = args.length > 0 ? args[0] : DEFAULT_EXCEL_PATH;
	run(data, excelPath);
    }

    /**
     * Runs selection logic: reads Excel from path, calls Qwen LLM, saves filled
     * form and output JSON with patient name.
     *
     * @param data      output.json as Map (e.g. JsonReadService.readOutputJson())
     * @param excelPath path to ED_EM Supplemental Tool.xlsx
     */
    public static void run(Map<String, Object> data, String excelPath) {
	ED_EMExcelReader excelReader = new ED_EMExcelReader(excelPath);
	LLMSelectionService selectionService = new LLMSelectionService(new LLMService(), excelReader);

	Map<String, Object> selections = selectionService.decideSelections(data);

	String patientName = extractPatientName(data);
	String safeName = toSafeFilename(patientName);

	// Save filled form HTML with selections (template unchanged)
	String htmlOutputPath = OUTPUT_DIR + "/autoCoderForm_" + safeName + ".html";
	boolean htmlSaved = ED_EMFormHtmlUpdater.updateToFile(FORM_TEMPLATE, htmlOutputPath, selections);
	if (htmlSaved) System.out.println("Saved filled form: " + htmlOutputPath + "\n");

	// Validate procedure and ICD codes from form against output.json
	ProcedureIcdValidator.validateProceduresIcdFromHtml(htmlOutputPath);

	// Save output.json copy with patient name
	String jsonOutputPath = OUTPUT_DIR + "/output_" + safeName + ".json";
	if (saveOutputWithPatientName(OUTPUT_JSON_PATH, jsonOutputPath))
	    System.out.println("Saved output JSON: " + jsonOutputPath + "\n");

	// Save selection summary as standalone HTML with h1, h2, h3, p tags
	String summaryHtmlPath = OUTPUT_DIR + "/selection_summary_" + safeName + ".html";
	boolean summarySaved = ED_EMFormHtmlUpdater.writeSelectionSummaryHtml(summaryHtmlPath, selections, patientName);
	if (summarySaved) {
	    System.out.println("Saved selection summary (HTML): " + summaryHtmlPath + "\n");
	}

	System.out.println("=== ED EM Supplemental Form - Qwen Selection ===\n");
	System.out.println("Patient: " + (patientName != null ? patientName : "(unknown)"));
	System.out.println("Input: output.json, Excel: " + excelPath);
	System.out.println("Form template: " + FORM_TEMPLATE + "\n");
	System.out.println("Qwen LLM analysis result (output.json + Excel -> analyze -> selections):\n");

	System.out.println(
		"--- PROBLEM(S) ADDRESSED (COPA sheet) [2 radios + 1 cb before dashes + 1 cb after dashes] ---");
	selections.entrySet().stream().filter(e -> e.getKey().startsWith("problem."))
		.forEach(e -> printSelection(e.getKey(), e.getValue()));

	System.out.println("\n--- DATA (DATA sheet) ---");
	selections.entrySet().stream().filter(e -> e.getKey().startsWith("data."))
		.forEach(e -> printSelection(e.getKey(), e.getValue()));

	System.out.println("\n--- RISK (RISK sheet) ---");
	selections.entrySet().stream().filter(e -> e.getKey().startsWith("risk."))
		.forEach(e -> printSelection(e.getKey(), e.getValue()));

	if (selections.isEmpty()) {
	    System.out.println("  (no selections from Qwen - check LLM availability)");
	}
    }

    /**
     * Runs LLM selection, applies choices to the live {@code #autoCoderForm} in the browser (modal or standalone),
     * and validates procedure/ICD fields from the live DOM. The on-disk template is not modified; optional
     * per-patient HTML snapshot and summary files are still written for audit.
     */
    public static void runOnPage(Page page, Map<String, Object> data, String excelPath) {
	if (page == null) {
	    System.out.println("runOnPage: page is null");
	    return;
	}
	ED_EMExcelReader excelReader = new ED_EMExcelReader(excelPath);
	LLMSelectionService selectionService = new LLMSelectionService(new LLMService(), excelReader);
	Map<String, Object> selections = selectionService.decideSelections(data);

	ED_EMFormPlaywrightApplier.applySelections(page, selections);
	System.out.println("Applied " + selections.size() + " selection(s) on live #autoCoderForm\n");

	String patientName = extractPatientName(data);
	String safeName = toSafeFilename(patientName);

	ProcedureIcdValidator.validateProceduresIcdFromPage(page);

	String htmlOutputPath = OUTPUT_DIR + "/autoCoderForm_" + safeName + ".html";
	boolean htmlSaved = ED_EMFormHtmlUpdater.updateToFile(FORM_TEMPLATE, htmlOutputPath, selections);
	if (htmlSaved) {
	    System.out.println("Saved filled form snapshot (optional): " + htmlOutputPath + "\n");
	}

	String jsonOutputPath = OUTPUT_DIR + "/output_" + safeName + ".json";
	if (saveOutputWithPatientName(OUTPUT_JSON_PATH, jsonOutputPath))
	    System.out.println("Saved output JSON: " + jsonOutputPath + "\n");

	String summaryHtmlPath = OUTPUT_DIR + "/selection_summary_" + safeName + ".html";
	boolean summarySaved = ED_EMFormHtmlUpdater.writeSelectionSummaryHtml(summaryHtmlPath, selections, patientName);
	if (summarySaved) {
	    System.out.println("Saved selection summary (HTML): " + summaryHtmlPath + "\n");
	}

	System.out.println("=== ED EM Supplemental Form - Qwen Selection (live page) ===\n");
	System.out.println("Patient: " + (patientName != null ? patientName : "(unknown)"));
	selections.entrySet().stream().filter(e -> e.getKey().startsWith("problem."))
		.forEach(e -> printSelection(e.getKey(), e.getValue()));
	selections.entrySet().stream().filter(e -> e.getKey().startsWith("data."))
		.forEach(e -> printSelection(e.getKey(), e.getValue()));
	selections.entrySet().stream().filter(e -> e.getKey().startsWith("risk."))
		.forEach(e -> printSelection(e.getKey(), e.getValue()));
	if (selections.isEmpty()) {
	    System.out.println("  (no selections from Qwen - check LLM availability)");
	}
    }

    /** Same as {@link #runOnPage(Page, Map, String)} with the default supplemental Excel path. */
    public static void runOnPage(Page page, Map<String, Object> data) {
	runOnPage(page, data, DEFAULT_EXCEL_PATH);
    }

    public static void printSelection(String name, Object value) {
	String label = FormFieldConstants.getLabel(name);
	String id = FormFieldConstants.getId(name);
	String action = formatAction(value);
	System.out.println("  " + name + " -> " + action);
	System.out.println("    label: \"" + label + "\"  id=\"" + id + "\"");
    }

    public static String formatAction(Object v) {
	if (v == null)
	    return "null";
	if (v instanceof Boolean b)
	    return b ? "check (select this)" : "uncheck";
	return "select " + v;
    }

    @SuppressWarnings("unchecked")
    private static String extractPatientName(Map<String, Object> data) {
	if (data == null)
	    return null;
	Object patient = data.get("patient");
	if (!(patient instanceof Map))
	    return null;
	Object name = ((Map<String, Object>) patient).get("name");
	return name != null ? name.toString().trim() : null;
    }

    private static String toSafeFilename(String name) {
	if (name == null || name.isBlank())
	    return "Unknown";
	return name.replaceAll("[^a-zA-Z0-9.-]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private static boolean saveOutputWithPatientName(String sourcePath, String targetPath) {
	try {
	    Path src = Path.of(sourcePath);
	    if (!Files.exists(src))
		src = Path.of(System.getProperty("user.dir"), sourcePath);
	    if (!Files.exists(src))
		return false;
	    Path tgt = Path.of(System.getProperty("user.dir"), targetPath);
	    Files.createDirectories(tgt.getParent());
	    Files.copy(src, tgt, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	    return true;
	} catch (Exception e) {
	    return false;
	}
    }
}
