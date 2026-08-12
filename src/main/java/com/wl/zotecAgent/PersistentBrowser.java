package com.wl.zotecAgent;

import com.microsoft.playwright.*;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Browser connection helper. Supports:
 * 1. Connect to existing browser (CDP) - Chrome started with --remote-debugging-port=9222
 * 2. Launch persistent context (new browser with user profile)
 */
public class PersistentBrowser {

    /** Default CDP URL to connect to existing Chrome with remote debugging. */
    public static final String DEFAULT_CDP_URL = "http://localhost:9222";

    /** Default user data dir for launchPersistentContext. */
    public static final String DEFAULT_USER_DATA_DIR = "C:\\playwright-profile";

    /**
     * Connects to an existing browser via CDP.
     * Start Chrome first: chrome.exe --remote-debugging-port=9222 --user-data-dir="C:\playwright"
     *
     * @param cdpUrl e.g. "http://localhost:9222"
     * @return Page from first context/tab, or null if none
     */
    public static Page connectToExistingBrowser(String cdpUrl) {
        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().connectOverCDP(cdpUrl);
        BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
        if (context.pages().isEmpty()) {
            return context.newPage();
        }
        return context.pages().get(0);
    }

    /**
     * Connects to existing browser at default CDP URL (localhost:9222).
     */
    public static Page connectToExistingBrowser() {
        return connectToExistingBrowser(DEFAULT_CDP_URL);
    }

    /**
     * Launches a new browser with persistent context (user profile).
     * Reuses existing tabs if profile already has pages open.
     *
     * @param userDataDir e.g. "C:\\playwright-profile"
     * @param headless    false to show browser window
     * @return Page (existing or new)
     */
    public static Page launchPersistentContext(String userDataDir, boolean headless) {
        Playwright playwright = Playwright.create();
        Path path = Paths.get(userDataDir);
        BrowserContext context = playwright.chromium().launchPersistentContext(path,
                new BrowserType.LaunchPersistentContextOptions().setHeadless(headless));
        if (context.pages().isEmpty()) {
            return context.newPage();
        }
        return context.pages().get(0);
    }

    /**
     * Returns a connection result with both Playwright and Page, for use when caller
     * needs to keep the connection open. Use try-with-resources or close manually.
     */
    public static BrowserConnection connect(String cdpUrl) {
        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().connectOverCDP(cdpUrl);
        BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
        Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
        return new BrowserConnection(playwright, browser, context, page);
    }

    public static BrowserConnection connect() {
        return connect(DEFAULT_CDP_URL);
    }

    /** Holder for connected browser resources. Call close() when done. */
    public static class BrowserConnection implements AutoCloseable {
        public final Playwright playwright;
        public final Browser browser;
        public final BrowserContext context;
        public final Page page;

        BrowserConnection(Playwright playwright, Browser browser, BrowserContext context, Page page) {
            this.playwright = playwright;
            this.browser = browser;
            this.context = context;
            this.page = page;
        }

        @Override
        public void close() {
            if (playwright != null) playwright.close();
        }
    }

    public static void main(String[] args) {
        // Connect to existing browser (ensure Chrome is running with --remote-debugging-port=9222)
        try (BrowserConnection conn = PersistentBrowser.connect()) {
            conn.page.navigate("https://google.com");
        }

        // Or launch new persistent context:
        // Page page = PersistentBrowser.launchPersistentContext(DEFAULT_USER_DATA_DIR, false);
        // page.navigate("https://google.com");
    }
}