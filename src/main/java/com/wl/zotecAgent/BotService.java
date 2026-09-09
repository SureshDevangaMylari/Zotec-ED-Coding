package com.wl.zotecAgent;

import java.util.List;

import org.springframework.stereotype.Service;

import com.microsoft.playwright.*;

@Service
public class BotService {

    private final FlowText flow2;

    public BotService(FlowText flow2) {
	this.flow2 = flow2;
    }

    static Thread botThread;
    static volatile boolean running = false;

    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;

    // 🔹 START (runs in new thread)
    public void startBot(List data, String bulkId, String agentId) {

	if (running) {
	    System.out.println("⚠ Bot already running");
	    return;
	}

	running = true;

	botThread = new Thread(() -> {
	    try {
		System.out.println("🚀 Bot STARTED");

		playwright = Playwright.create();

		BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setChannel("chrome")
			.setHeadless(false).setArgs(List.of("--start-maximized"));

		browser = playwright.chromium().launch(launchOptions);

		context = browser
			.newContext(new Browser.NewContextOptions().setAcceptDownloads(true).setViewportSize(null));

		page = context.newPage();

		BrowserCacheClearer.clearAll(context, page, "bot-start");

		flow2.Start(context, agentId);
		Thread.sleep(1000);

	    } catch (Exception e) {
		e.printStackTrace();
	    } finally {
		cleanup();
		System.out.println("🛑 Bot Thread Exited");
	    }
	});

	botThread.start();
    }

    // 🔹 STOP (can be called from another thread)
    static void stopBot() {
	System.out.println("🛑 Bot STOP requested");
	try {
	    BrowserCacheClearer.clearAll(context, page, "bot-stop");
	    if (page != null)
		page.close();
	    if (context != null)
		context.close();
	    if (browser != null)
		browser.close();
	    if (playwright != null)
		playwright.close();

	    if (running) {
		botThread.interrupt();
		page = null;
		context = null;
		browser = null;
		playwright = null;
	    }
	} catch (Exception e) {
	    // TODO: handle exception
	}

	running = false;

    }

    // 🔹 CLEANUP
    static void cleanup() {
	try {
	    BrowserCacheClearer.clearAll(context, page, "bot-cleanup");
	    if (page != null)
		page.close();
	    if (context != null)
		context.close();
	    if (browser != null)
		browser.close();
	    if (playwright != null)
		playwright.close();
	} catch (Exception e) {
	    e.printStackTrace();
	} finally {
	    page = null;
	    context = null;
	    browser = null;
	    playwright = null;
	}
    }

}
