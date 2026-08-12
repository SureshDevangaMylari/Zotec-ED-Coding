package com.wl.zotecAgent;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.wl.util.BackendAgentControlClient;
import com.wl.util.BackendAgentControlClient.AgentStatus;

/**
 * Backend controls the agent: backend flips START / STOP,
 * this controller runs the Playwright agent and reacts in real time.
 */
public class AgentController {

    private static final Logger logger = LogManager.getLogger(AgentController.class);
    private static final int POLL_INTERVAL_MS = 2000;

    private static volatile boolean running = false;
    private static final AtomicReference<Playwright> playwrightRef = new AtomicReference<>();
    private static final AtomicReference<Browser> browserRef = new AtomicReference<>();
    private static final AtomicReference<BrowserContext> contextRef = new AtomicReference<>();
    private static Thread agentThread;

    public static void main(String[] args) {
        logger.info("Agent controller started. Polling backend at {} for START/STOP.", BackendAgentControlClient.BASE_URL);

        while (true) {
            try {
                AgentStatus status = BackendAgentControlClient.getAgentStatus();

                if (status == AgentStatus.START && !running) {
                    logger.info("Backend said START — starting Playwright agent.");
                    startAgent();
                } else if (status == AgentStatus.STOP && running) {
                    logger.info("Backend said STOP — stopping Playwright agent.");
                    stopAgent();
                } else if (status == AgentStatus.UNKNOWN) {
                    logger.debug("Backend status unknown or unreachable.");
                }

                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Controller interrupted.");
                stopAgent();
                break;
            } catch (Exception e) {
                logger.error("Error in controller loop", e);
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        logger.info("Agent controller stopped.");
    }

    private static void startAgent() {
        if (agentThread != null && agentThread.isAlive()) {
            return;
        }

        agentThread = new Thread(() -> {
            Playwright playwright = null;
            Browser browser = null;
            BrowserContext context = null;
            try {
                playwright = Playwright.create();
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(false)
                        .setSlowMo(1000)
                        .setArgs(List.of("--start-maximized", "--disable-blink-features=AutomationControlled")));
                context = browser.newContext(new Browser.NewContextOptions()
                        .setViewportSize(null)
                        .setIgnoreHTTPSErrors(true));
                context.setDefaultNavigationTimeout(60000);
                context.setDefaultTimeout(10000);

                playwrightRef.set(playwright);
                browserRef.set(browser);
                contextRef.set(context);
                running = true;

//                Flow.Start(context);
            } catch (Exception e) {
                logger.error("Agent flow error", e);
            } finally {
                running = false;
                playwrightRef.set(null);
                browserRef.set(null);
                contextRef.set(null);
                try {
                    if (context != null) context.close();
                } catch (Exception ignored) {}
                try {
                    if (browser != null) browser.close();
                } catch (Exception ignored) {}
                try {
                    if (playwright != null) playwright.close();
                } catch (Exception ignored) {}
                logger.info("Agent run finished.");
            }
        }, "playwright-agent");
        agentThread.start();
    }

    private static void stopAgent() {
        BrowserContext ctx = contextRef.getAndSet(null);
        Browser br = browserRef.getAndSet(null);
        Playwright pw = playwrightRef.getAndSet(null);

        running = false;
        try {
            if (ctx != null) ctx.close();
        } catch (Exception e) {
            logger.debug("Error closing context on stop", e);
        }
        try {
            if (br != null) br.close();
        } catch (Exception e) {
            logger.debug("Error closing browser on stop", e);
        }
        try {
            if (pw != null) pw.close();
        } catch (Exception e) {
            logger.debug("Error closing playwright on stop", e);
        }
    }
}
