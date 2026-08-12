package com.wl.zotecAgent;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.wl.util.PlaywrightService;

public class Pause {
    public static final Logger logger = LogManager.getLogger(PlayTest.class);

    public static void main(String[] args) throws Exception {
	String casenumber = "PT002";
	String pin = "029378";
	try (Playwright playwright = Playwright.create()) {
	    Browser browser = playwright.chromium().connectOverCDP("http://localhost:9222");

	    // Use eisting context or create a new one
	    BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
	    Map data = new HashMap();
	    data.put("patientName", "Treasure Phillippi");
	    data.put("lastName", data);
	    Page page = context.pages().get(0);
		page.pause();
	    }
	    // ---- LOGIN FLOW ----

	}
    }
