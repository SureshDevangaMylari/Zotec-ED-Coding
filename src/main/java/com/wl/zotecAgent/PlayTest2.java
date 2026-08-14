package com.wl.zotecAgent;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.wl.util.JsonReadService;
import com.wl.util.PlaywrightService;
import com.wl.zotecAgent.selection.ED_EMFormPlaywrightApplier;

/**
 * Standalone test runner: reads a resume JSON file and applies it to the open
 * Coding Workfile page.
 * <p>
 * Prerequisite: Chrome with remote debugging on port 9222 and Coding Workfile
 * tab open.
 * 
 * <pre>
 * "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222 --user-data-dir="C:\plawright"
 * mvn exec:java -Dexec.mainClass=com.wl.zotecAgent.PlayTest2
 * mvn exec:java -Dexec.mainClass=com.wl.zotecAgent.PlayTest2 -Dexec.args="resources/jsonfolder/resume-payload-alexis-divine.json"
 * </pre>
 */
public class PlayTest2 {

    public static final Logger logger = LogManager.getLogger(PlayTest2.class);

    private static final String DEFAULT_RESUME_JSON = "resources/jsonfolder/review-3df8f7c9-75dd-4e41-a2c6-5f82f1597ee2.json";

    public static void main(String[] args) throws Exception {
	String jsonPath = args.length > 0 ? args[0] : DEFAULT_RESUME_JSON;

	JsonReadService reader = new JsonReadService();
	Map<String, Object> resumePayload = reader.readFromPath(jsonPath);
	if (resumePayload.isEmpty()) {
	    logger.error("Resume payload empty or missing: {}", jsonPath);
	    return;
	}

	logger.info("Loaded resume payload from {} (keys: {})", jsonPath, resumePayload.keySet());

	PlayTestActionLog.enable();
	try (Playwright playwright = Playwright.create()) {
	    Browser browser = playwright.chromium().connectOverCDP("http://localhost:9222");
	    BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
	    Page page = findCodingWorkfilePage(context);
	    PlaywrightService ps = new PlaywrightService(page);

	    if (page.getByText("This report has been completed.").count() > 0
		    && page.getByText("This report has been completed.").first().isVisible()) {
		logger.info("This report has been completed. — skipping (PlayTest2 has no next patient)");
		PlayTestActionLog.skip("Patient", "This report has been completed.");
		page.pause();
		return;
	    }

	    Map<String, Object> patientInfo = ResumePayloadMapper.toValidationMap(resumePayload);
	    patientInfo.put("resume_payload", resumePayload);
	    patientInfo.put("source", "resume_json_file");
	    patientInfo.put("resume_json_path", jsonPath);

	    ZotecService zs=new ZotecService();
	    PlayTestActionLog.step("validatePatientDetails");
	    logger.info("Running validatePatientDetails from {}", jsonPath);
	     zs.validatePatientDetails(page, patientInfo);
	     
	     Thread.sleep(400);

	    PlayTestActionLog.step("open ED form");
	    ps.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("ED")), "clicking ED");
	    page.locator("#codingAssistantBody #autoCoderForm, #autoCoderForm").first()
		    .waitFor(new Locator.WaitForOptions()
			    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
			    .setTimeout(15_000));
	    Thread.sleep(300);

	    if (ResumePayloadMapper.shouldUseCriticalCareForm(resumePayload)
		    || ED_EMFormPlaywrightApplier.isCriticalCareFormVisible(page)) {
		logger.info("Applying Critical Care ED form from JSON");
		PlayTestActionLog.step("ED Critical Care form");
	    } else {
		PlayTestActionLog.step("ED EM form");
	    }
	    ED_EMFormPlaywrightApplier.applyEdFormFromResume(page, resumePayload);

            ps.click(page.locator("input[type=\"submit\"]"), "ed submit");

	    Service s = new Service();
	    List<Map<String, Object>> cptEntries = ResumePayloadMapper.extractCptEntries(resumePayload);
	    List<String> icdList = ResumePayloadMapper.extractIcdCodeList(resumePayload);

	    logger.info("validateCPT entries: {}", cptEntries);
	    logger.info("validateICD codes: {}", icdList);

	    // Order: CPT → ICD (once) → accident/billing extras → Move to Issue OR RFI (optional, mutually exclusive)
	    s.validateCPT(page, cptEntries, icdList);
	    s.validateICD(icdList, page);
	    new CodingFormValidationService(page).updateBillingExtras(patientInfo);
	    IssueOrRfiApplier.applyAfterCodingFill(page, resumePayload);

	    logger.info("Resume JSON test run completed");
	    page.pause();
	} finally {
	    PlayTestActionLog.disable();
	}
    }

    private static Page findCodingWorkfilePage(BrowserContext context) {
	for (Page p : context.pages()) {
	    if (p.title().contains("Coding Workfile")) {
		logger.info("Found Coding Workfile tab");
		return p;
	    }
	}
	if (context.pages().isEmpty()) {
	    throw new IllegalStateException(
		    "No browser pages open. Start Chrome with --remote-debugging-port=9222 and open Coding Workfile.");
	}
	logger.warn("Coding Workfile tab not found; using first open page");
	return context.pages().get(0);
    }

}
