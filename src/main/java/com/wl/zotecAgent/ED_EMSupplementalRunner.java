package com.wl.zotecAgent;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.wl.util.PlaywrightService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Runner that reads output.json and fills the ED EM Supplemental Tool modal.
 * <p>
 * Prerequisites: - Chrome running with remote debugging: chrome.exe
 * --remote-debugging-port=9222 - Coding Workfile tab open with the ED EM
 * Supplemental modal (#codingAssistantBody) visible -
 * resources/jsonfolder/output.json contains extracted clinical data -
 * resources/ED_EM Supplemental Tool.xlsx present
 * <p>
 * Run: mvn exec:java
 * -Dexec.mainClass="com.wl.zotecAgent.ED_EMSupplementalRunner" Or: java -cp ...
 * com.wl.zotecAgent.ED_EMSupplementalRunner
 */
public class ED_EMSupplementalRunner {

    private static final Logger log = LogManager.getLogger(ED_EMSupplementalRunner.class);
    private static final String CDP_URL = "http://localhost:9222";

    public static void main(String[] args) {
	try (Playwright playwright = Playwright.create()) {
	    Browser browser = playwright.chromium().connectOverCDP(CDP_URL);
	    BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);

	    Page page = context.pages().get(0);
	    if (page == null) {
		page = context.pages().isEmpty() ? null : context.pages().get(0);
		log.warn("Coding Workfile tab not found, using first tab");
	    } else {
		log.info("Found Coding Workfile tab");
	    }

	    if (page == null) {
		log.error("No page available. Ensure Chrome is open with at least one tab.");
		return;
	    }

	    run(page);
	} catch (Exception e) {
	    log.error("Runner failed", e);
	    e.printStackTrace();
	}
    }

    /**
     * Fills the ED EM Supplemental form using output.json. Call this when the modal
     * #codingAssistantBody is visible.
     */
    public static void run(Page page) {
	log.info("Starting ED EM Supplemental form fill from output.json");

	ED_EMSupplementalFormFiller filler = new ED_EMSupplementalFormFiller(page);

	PlaywrightService ps = new PlaywrightService(page);
	try {
	    ps.waitForElement("#codingAssistantBody", "ED EM Supplemental modal", 10);
	} catch (Exception e) {
	    log.warn("Modal #codingAssistantBody not visible. Open the ED EM Supplemental modal and run again. {}",
		    e.getMessage());
	}

	filler.fillFromOutputJson();
	log.info("ED EM Supplemental form fill complete");
    }

    private static Page findCodingWorkfilePage(BrowserContext context) {
	for (Page p : context.pages()) {
	    try {
		if (p.title() != null && p.title().toLowerCase().contains("coding"))
		    return p;
	    } catch (Exception ignored) {
	    }
	}
	return null;
    }
}
