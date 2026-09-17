package com.wl.zotecAgent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final Flow flow;
    private final FlowText flowText;

    @Value("${flow.agent-id:698ae5c9b0bf82d7668c29c8}")
    private String defaultAgentId;

    private Thread botThread;
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    private volatile String activeChartType;
    private volatile List<String> activeClients = List.of();

    public BotService(Flow flow, FlowText flowText) {
	this.flow = flow;
	this.flowText = flowText;
    }

    public boolean isRunning() {
	return running;
    }

    public boolean isStopRequested() {
	return stopRequested;
    }

    public String getActiveChartType() {
	return activeChartType;
    }

    public List<String> getActiveClients() {
	return activeClients == null ? List.of() : List.copyOf(activeClients);
    }

    /**
     * Legacy entry (e.g. FlowStartupRunner): defaults to FlowText with full AllowedClients.
     */
    public void startBot(List<?> data, String bulkId, String agentId) {
	startBotFromUi("Text", AllowedClients.orderedEntries(), agentId);
    }

    /**
     * Start from UI JSON: chartType Text → {@link FlowText}, Image → {@link Flow}.
     * Only the given client labels are walked in the Zotec Select client(s) panel.
     */
    public synchronized boolean startBotFromUi(String chartType, List<String> clients) {
	return startBotFromUi(chartType, clients, defaultAgentId);
    }

    public synchronized boolean startBotFromUi(String chartType, List<String> clients, String agentId) {
	if (running) {
	    log.warn("Bot already running");
	    return false;
	}
	if (clients == null || clients.isEmpty()) {
	    log.warn("No clients provided — refuse start");
	    return false;
	}

	final String type = chartType != null ? chartType.trim() : "Text";
	final List<String> selected = Collections.unmodifiableList(new ArrayList<>(clients));
	final String aid = (agentId != null && !agentId.isBlank()) ? agentId : defaultAgentId;

	running = true;
	stopRequested = false;
	activeChartType = type;
	activeClients = selected;

	botThread = new Thread(() -> {
	    try {
		log.info("Bot STARTED chartType={} clients={}", type, selected.size());

		playwright = Playwright.create();
		BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setChannel("chrome")
			.setHeadless(false).setArgs(List.of("--start-maximized"));
		browser = playwright.chromium().launch(launchOptions);
		context = browser
			.newContext(new Browser.NewContextOptions().setAcceptDownloads(true).setViewportSize(null));
		page = context.newPage();

		BrowserCacheClearer.clearAll(context, page, "bot-start");

		if ("Image".equalsIgnoreCase(type)) {
		    flow.Start(context, aid, selected);
		} else {
		    flowText.Start(context, aid, selected);
		}
		Thread.sleep(1000);
	    } catch (Exception e) {
		if (!stopRequested) {
		    log.error("Bot run failed", e);
		} else {
		    log.info("Bot stopped: {}", e.getMessage());
		}
	    } finally {
		cleanup();
		running = false;
		stopRequested = false;
		activeChartType = null;
		activeClients = List.of();
		log.info("Bot Thread Exited");
	    }
	}, "zotec-bot");

	botThread.start();
	return true;
    }

    /** Stop Playwright and clear active UI selection state. */
    public synchronized void stopBot() {
	log.info("Bot STOP requested");
	stopRequested = true;
	try {
	    BrowserCacheClearer.clearAll(context, page, "bot-stop");
	    if (page != null) {
		page.close();
	    }
	    if (context != null) {
		context.close();
	    }
	    if (browser != null) {
		browser.close();
	    }
	    if (playwright != null) {
		playwright.close();
	    }
	} catch (Exception e) {
	    log.debug("stopBot close: {}", e.getMessage());
	} finally {
	    page = null;
	    context = null;
	    browser = null;
	    playwright = null;
	    if (botThread != null) {
		botThread.interrupt();
	    }
	    running = false;
	    activeChartType = null;
	    activeClients = List.of();
	}
    }

    void cleanup() {
	try {
	    BrowserCacheClearer.clearAll(context, page, "bot-cleanup");
	    if (page != null) {
		page.close();
	    }
	    if (context != null) {
		context.close();
	    }
	    if (browser != null) {
		browser.close();
	    }
	    if (playwright != null) {
		playwright.close();
	    }
	} catch (Exception e) {
	    log.debug("cleanup: {}", e.getMessage());
	} finally {
	    page = null;
	    context = null;
	    browser = null;
	    playwright = null;
	}
    }
}
