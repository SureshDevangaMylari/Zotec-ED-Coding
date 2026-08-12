package com.wl.util;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Utility class for managing existing Playwright browser sessions via CDP (Chrome DevTools Protocol).
 * Connects to an existing browser instance running with remote debugging enabled.
 * 
 * Usage:
 * Chrome must be started with: --remote-debugging-port=9222
 * Example: "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222 --user-data-dir="C:\chrome-debug-profile"
 */
public class PlaywrightSessionManager {
    private static final Logger logger = LogManager.getLogger(PlaywrightSessionManager.class);
    private static final String DEFAULT_CDP_URL = "http://localhost:9222";
    
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private String cdpUrl;

    /**
     * Creates a new PlaywrightSessionManager with default CDP URL (http://localhost:9222).
     */
    public PlaywrightSessionManager() {
	this(DEFAULT_CDP_URL);
    }

    /**
     * Creates a new PlaywrightSessionManager with a custom CDP URL.
     * 
     * @param cdpUrl The CDP endpoint URL (e.g., "http://localhost:9222")
     */
    public PlaywrightSessionManager(String cdpUrl) {
	this.cdpUrl = cdpUrl;
	initialize();
    }

    /**
     * Initializes the connection to the existing browser session.
     */
    private void initialize() {
	try {
	    playwright = Playwright.create();
	    browser = playwright.chromium().connectOverCDP(cdpUrl);
	    logger.info("Connected to existing browser session at: {}", cdpUrl);

	    // Use existing context or create a new one
	    List<BrowserContext> contexts = browser.contexts();
	    if (contexts.isEmpty()) {
		context = browser.newContext();
		logger.info("Created new browser context");
	    } else {
		context = contexts.get(0);
		logger.info("Using existing browser context ({} contexts available)", contexts.size());
	    }
	} catch (Exception e) {
	    logger.error("Error connecting to browser session at {}: {}", cdpUrl, e.getMessage());
	    e.printStackTrace();
	    throw new RuntimeException("Failed to connect to browser session", e);
	}
    }

    /**
     * Gets the Playwright instance.
     * 
     * @return The Playwright instance
     */
    public Playwright getPlaywright() {
	return playwright;
    }

    /**
     * Gets the Browser instance connected via CDP.
     * 
     * @return The Browser instance
     */
    public Browser getBrowser() {
	return browser;
    }

    /**
     * Gets the BrowserContext. Uses existing context if available, otherwise creates a new one.
     * 
     * @return The BrowserContext instance
     */
    public BrowserContext getContext() {
	return context;
    }

    /**
     * Gets a page by index. Returns the first page (index 0) by default.
     * 
     * @param index The page index (0-based)
     * @return The Page instance
     * @throws IndexOutOfBoundsException If the index is out of bounds
     */
    public Page getPage(int index) {
	List<Page> pages = context.pages();
	if (pages.isEmpty()) {
	    logger.warn("No pages available, creating a new page");
	    return context.newPage();
	}
	if (index < 0 || index >= pages.size()) {
	    throw new IndexOutOfBoundsException(
		    String.format("Page index %d is out of bounds. Available pages: %d", index, pages.size()));
	}
	logger.info("Retrieved page at index {} (total pages: {})", index, pages.size());
	return pages.get(index);
    }

    /**
     * Gets the first page (index 0). Creates a new page if none exists.
     * 
     * @return The first Page instance
     */
    public Page getFirstPage() {
	return getPage(0);
    }

    /**
     * Gets the second page (index 1). Creates a new page if it doesn't exist.
     * 
     * @return The second Page instance, or a new page if only one page exists
     */
    public Page getSecondPage() {
	List<Page> pages = context.pages();
	if (pages.size() < 2) {
	    logger.info("Second page doesn't exist, creating a new page");
	    return context.newPage();
	}
	return pages.get(1);
    }

    /**
     * Gets a page by index safely. Returns null if the page doesn't exist instead of throwing an exception.
     * 
     * @param index The page index (0-based)
     * @return The Page instance, or null if index is out of bounds
     */
    public Page getPageSafely(int index) {
	try {
	    return getPage(index);
	} catch (IndexOutOfBoundsException e) {
	    logger.warn("Page at index {} doesn't exist: {}", index, e.getMessage());
	    return null;
	}
    }

    /**
     * Creates a new page in the current context.
     * 
     * @return The newly created Page instance
     */
    public Page createNewPage() {
	Page newPage = context.newPage();
	logger.info("Created new page (total pages: {})", context.pages().size());
	return newPage;
    }

    /**
     * Gets the total number of pages in the current context.
     * 
     * @return The number of pages
     */
    public int getPageCount() {
	return context.pages().size();
    }

    /**
     * Closes the browser connection and cleans up resources.
     * Note: This will close the connection but not the actual browser instance.
     */
    public void close() {
	try {
	    if (playwright != null) {
		playwright.close();
		logger.info("Closed Playwright session manager");
	    }
	} catch (Exception e) {
	    logger.error("Error closing Playwright session manager: {}", e.getMessage());
	}
    }

    /**
     * Static helper method to quickly get a browser context from default CDP URL.
     * 
     * @return BrowserContext connected to existing browser session
     */
    public static BrowserContext getDefaultContext() {
	PlaywrightSessionManager manager = new PlaywrightSessionManager();
	return manager.getContext();
    }

    /**
     * Static helper method to quickly get the first page from default CDP URL.
     * 
     * @return First Page from existing browser session
     */
    public static Page getDefaultFirstPage() {
	PlaywrightSessionManager manager = new PlaywrightSessionManager();
	return manager.getFirstPage();
    }

    /**
     * Static helper method to quickly get the second page from default CDP URL.
     * Creates a new page if second page doesn't exist.
     * 
     * @return Second Page from existing browser session, or a new page
     */
    public static Page getDefaultSecondPage() {
	PlaywrightSessionManager manager = new PlaywrightSessionManager();
	return manager.getSecondPage();
    }
}

