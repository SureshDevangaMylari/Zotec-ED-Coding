package com.wl.zotecAgent;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.wl.util.JsonFileUtil;

/**
 * Desktop review UI at {@code desktop.review.url} (default {@code http://10.1.242.218:8001/}).
 * <p>
 * Open + login happen <b>once</b> per bot session. Each chart: upload current workfile,
 * keep that document_id, refresh, wait 3s, Start processing, wait for Submit review,
 * then poll {@code GET /records/{id}/review} and fill Zotec.
 */
@Service
public class DesktopReviewPortalService {

    private static final Logger log = LoggerFactory.getLogger(DesktopReviewPortalService.class);

    @Value("${desktop.review.url:http://10.1.242.218:8001/}")
    private String reviewUrl;

    @Value("${zotec.portal.username}")
    private String username;

    @Value("${zotec.portal.password}")
    private String password;

    @Value("${batch.resume.timeout-minutes:60}")
    private long timeoutMinutes;

    private Page portalPage;
    private boolean loggedIn;
    /** Queue document_id currently being processed on :8001. */
    private String processingDocumentId;

    private final DocumentQueueCleanupService documentQueueCleanup;
    private final RecordReviewPollingService recordReviewPolling;

    public DesktopReviewPortalService(DocumentQueueCleanupService documentQueueCleanup,
	    RecordReviewPollingService recordReviewPolling) {
	this.documentQueueCleanup = documentQueueCleanup;
	this.recordReviewPolling = recordReviewPolling;
    }

    /**
     * Open review URL in a new tab and sign in once per bot run. Cookies for this URL are
     * cleared first so username/password are always required. Same-run calls reuse the tab.
     */
    public synchronized Page ensureOpenAndLoggedIn(BrowserContext context) throws InterruptedException {
	if (portalPage != null && !portalPage.isClosed() && loggedIn) {
	    portalPage.bringToFront();
	    return portalPage;
	}

	if (username == null || username.isBlank() || password == null || password.isBlank()) {
	    throw new IllegalStateException(
		    "zotec.portal.username / zotec.portal.password must be set in the active profile");
	}

	String url = reviewUrl == null || reviewUrl.isBlank() ? "http://10.1.242.218:8001/" : reviewUrl;
	if (!url.endsWith("/")) {
	    url = url + "/";
	}

	// Drop prior session so each Start Agent run gets the login form
	loggedIn = false;
	Page clearPage = null;
	try {
	    if (portalPage != null && !portalPage.isClosed()) {
		clearPage = portalPage;
	    } else if (context.pages() != null && !context.pages().isEmpty()) {
		clearPage = context.pages().get(0);
	    }
	} catch (Exception ignored) {
	}
	BrowserCacheClearer.clearDesktopReviewSiteOnly(context, clearPage, url,
		"before-desktop-review-login");

	log.info("Opening desktop review portal (once) {}", url);
	if (portalPage == null || portalPage.isClosed()) {
	    portalPage = context.newPage();
	}
	portalPage.navigate(url);
	try {
	    portalPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
	} catch (Exception ignored) {
	}
	Thread.sleep(1000);

	login(portalPage);
	loggedIn = true;
	portalPage.bringToFront();
	return portalPage;
    }

    /**
     * Ensures portal session, keeps {@code keepDocumentId} in the queue (deletes others),
     * refreshes, waits 3s, clicks Start processing, then waits for Submit review + JSON.
     *
     * @param refreshCount 1 for text charts, 2 for image charts — every chart
     * @param keepDocumentId document_id from uploading the current Zotec chart
     */
    public Map<String, Object> awaitCodingJsonAfterSubmitReview(BrowserContext context,
	    int refreshCount, String keepDocumentId) throws InterruptedException {
	Page portal = ensureOpenAndLoggedIn(context);
	portal.bringToFront();

	processingDocumentId = keepDocumentId;
	log.info("Clearing older documents in queue, keeping uploaded document_id={}", currentDocumentId());
	documentQueueCleanup.clearQueuedDocumentsExcept(keepDocumentId);
	log.info("Queue cleanup done — will process document_id={}", currentDocumentId());

	int refreshes = Math.max(0, refreshCount);
	log.info("Desktop review: refreshing {} time(s) before Start processing", refreshes);
	for (int i = 1; i <= refreshes; i++) {
	    log.info("Desktop review reload {}/{}", i, refreshes);
	    portal.reload();
	    try {
		portal.waitForLoadState(LoadState.DOMCONTENTLOADED);
	    } catch (Exception ignored) {
	    }
	    Thread.sleep(1000);
	}
	log.info("Desktop review: waiting 3s before Start processing");
	Thread.sleep(3000);

	clickStartProcessing(portal);
	waitUntilSubmitReviewClicked(portal);
	log.info("Submit review clicked — polling backend GET /records/{}/review documentNumber={}",
		currentDocumentId(), currentDocumentId());
	Map<String, Object> json = pollBackendReviewJson(currentDocumentId());
	try {
	    JsonFileUtil.saveToJsonFileAtPath(json, "resources/jsonfolder/output.json", true);
	} catch (Exception e) {
	    log.warn("Could not save desktop-review JSON to output.json: {}", e.getMessage());
	}
	return json;
    }

    /** {@code document_id} kept from GET /documents (the chart :8001 is processing). */
    public String currentDocumentId() {
	return (processingDocumentId == null || processingDocumentId.isBlank())
		? "unknown"
		: processingDocumentId;
    }

    private void login(Page page) throws InterruptedException {
	log.info("Desktop review login as {}", username);

	Locator user = page.locator("input.th-input[autocomplete='username']").first();
	if (user.count() == 0 || !user.isVisible()) {
	    user = page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("USERNAME"))
		    .first();
	}
	if (user.count() == 0 || !user.isVisible()) {
	    user = page.locator("input.th-input").first();
	}
	user.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30_000));
	user.fill(username);

	Locator pass = page.locator("input.th-input[autocomplete='current-password']").first();
	if (pass.count() == 0 || !pass.isVisible()) {
	    pass = page.locator("input.th-input[type='password']").first();
	}
	pass.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(15_000));
	pass.fill(password);
	Thread.sleep(400);

	Locator signIn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in"))
		.first();
	if (signIn.count() == 0) {
	    signIn = page.locator("button[type='submit']").filter(
		    new Locator.FilterOptions().setHasText("Sign in")).first();
	}
	for (int i = 0; i < 20; i++) {
	    if (signIn.count() > 0 && signIn.isEnabled()) {
		break;
	    }
	    Thread.sleep(250);
	}
	signIn.click(new Locator.ClickOptions().setTimeout(15_000));
	try {
	    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
	} catch (Exception ignored) {
	}
	Thread.sleep(1500);
	log.info("Desktop review Sign in clicked");
    }

    private void clickStartProcessing(Page page) throws InterruptedException {
	Locator start = page.getByRole(AriaRole.BUTTON,
		new Page.GetByRoleOptions().setName("Start processing")).first();
	if (start.count() == 0 || !start.isVisible()) {
	    start = page.locator("button").filter(
		    new Locator.FilterOptions().setHasText("Start processing")).first();
	}
	start.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(60_000));
	for (int i = 0; i < 40; i++) {
	    if (start.isEnabled()) {
		break;
	    }
	    Thread.sleep(250);
	}
	log.info("Clicking desktop review Start processing");
	try {
	    start.click(new Locator.ClickOptions().setTimeout(15_000));
	} catch (Exception e) {
	    log.warn("Start processing normal click failed ({}) — force", e.getMessage());
	    start.click(new Locator.ClickOptions().setForce(true).setTimeout(15_000));
	}
	Thread.sleep(1000);
    }

    /**
     * Waits until the user clicks Submit review. JSON is then loaded via backend poll.
     */
    private void waitUntilSubmitReviewClicked(Page page) throws InterruptedException {
	installSubmitReviewClickHook(page);
	log.info("Waiting for USER to click Submit review (timeout {} min) documentNumber={}",
		timeoutMinutes, currentDocumentId());

	long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
	int tick = 0;
	while (System.currentTimeMillis() < deadline) {
	    if (isSubmitReviewClicked(page)) {
		log.info("Submit review click detected documentNumber={}", currentDocumentId());
		return;
	    }
	    tick++;
	    if (tick % 30 == 0) {
		log.info("Still waiting for USER to click Submit review... ({}s) documentNumber={}",
			tick, currentDocumentId());
	    }
	    Thread.sleep(1000);
	}
	throw new IllegalStateException(
		"Timed out waiting for Submit review after " + timeoutMinutes + " minute(s)");
    }

    /**
     * Same poll as before: {@code GET /records/{document_id}/review} until coding JSON is ready.
     */
    private Map<String, Object> pollBackendReviewJson(String documentId) throws InterruptedException {
	if (documentId == null || documentId.isBlank() || "unknown".equals(documentId)) {
	    throw new IllegalStateException("Cannot poll review — document_id is missing");
	}
	try {
	    Map<String, Object> reviewResult = recordReviewPolling.reviewUploadPdf(documentId);
	    Map<String, Object> resume = new LinkedHashMap<>(reviewResult);
	    resume.remove("review_response");
	    resume.remove("poll_attempts");
	    if (resume.isEmpty()) {
		throw new IllegalStateException("Backend review poll returned empty payload for document_id="
			+ documentId);
	    }
	    log.info("Backend review JSON ready documentNumber={} keys={}", documentId, resume.keySet());
	    return resume;
	} catch (java.util.concurrent.TimeoutException e) {
	    throw new IllegalStateException("Timed out polling backend review for document_id=" + documentId, e);
	}
    }

    private void installSubmitReviewClickHook(Page page) {
	try {
	    page.evaluate("() => {"
		    + "  window.__wlSubmitReviewClicked = false;"
		    + "  if (window.__wlSubmitReviewHooked) return;"
		    + "  window.__wlSubmitReviewHooked = true;"
		    + "  document.addEventListener('click', (e) => {"
		    + "    const t = e.target && e.target.closest ? e.target.closest('button') : null;"
		    + "    if (!t) return;"
		    + "    const label = (t.textContent || '').replace(/\\s+/g, ' ').trim();"
		    + "    if (label === 'Submit review') window.__wlSubmitReviewClicked = true;"
		    + "  }, true);"
		    + "}");
	} catch (Exception e) {
	    log.warn("Could not install Submit review click hook: {}", e.getMessage());
	}
    }

    private boolean isSubmitReviewClicked(Page page) {
	try {
	    Object v = page.evaluate("() => !!window.__wlSubmitReviewClicked");
	    return Boolean.TRUE.equals(v);
	} catch (Exception e) {
	    return false;
	}
    }

    private boolean isStartProcessingVisible(Page page) {
	try {
	    Locator b = page.getByRole(AriaRole.BUTTON,
		    new Page.GetByRoleOptions().setName("Start processing")).first();
	    return b.count() > 0 && b.isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    private boolean isSubmitReviewVisible(Page page) {
	try {
	    Locator b = page.getByRole(AriaRole.BUTTON,
		    new Page.GetByRoleOptions().setName("Submit review")).first();
	    return b.count() > 0 && b.isVisible();
	} catch (Exception e) {
	    return false;
	}
    }
}
