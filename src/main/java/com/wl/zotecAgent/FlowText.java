package com.wl.zotecAgent;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.wl.util.FileUtil;
import com.wl.util.PlaywrightService;
import com.wl.zotecAgent.selection.ED_EMFormPlaywrightApplier;

//   "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222 --user-data-dir="C:\playwright"
@Component
public class FlowText {
    public static final Logger logger = LogManager.getLogger(FlowText.class);
    public static Map<String, Object> patientInfo = new LinkedHashMap<>();
    static Date d = new Date();
    static SimpleDateFormat f = new SimpleDateFormat("MM-dd-yyyy");
    static String date = f.format(d);
    public static boolean isaides = false;
    static String PatientDOB = "";
    public static LinkedHashMap<String, String> ExcelObj = new LinkedHashMap<>();
    static String accountNumber;

    private static final String CLIENT_CHECKBOX_XPATH =
	    "//*[@class='badge badge-info pull-right ng-binding']/preceding-sibling::input";
    /** Bootstrap/Angular toggle: {@code <a class="dropdown-toggle" ng-click="refreshLocationFilter()">}. */
    private static final String SELECT_CLIENTS_TOGGLE =
	    "a.dropdown-toggle[ng-click*='refreshLocationFilter']";
    /** Dropdown panel shown when {@code li.dropdown} has class {@code open}. */
    private static final String CLIENT_DROPDOWN_OPEN = "li.dropdown.open #myDropdown";
    private static final String NO_MORE_REPORTS =
	    "There are no more reports to view based on your filters";
    private static final String REPORT_COMPLETED = "This report has been completed.";
    private static final String DATA_LOCKED_TITLE = "Data Locked";
    private static final String DATA_LOCKED_BODY =
	    "The data cannot be submitted because it is locked for edit by another user";

    private final ZotecService zs;
    private final DocumentProcessingService documentProcessing;

    @Autowired
    public FlowText(ZotecService zs, DocumentProcessingService documentProcessing) {
	this.zs = zs;
	this.documentProcessing = documentProcessing;
    }

    public void Start(BrowserContext context, String agentId) throws Exception {
	Start(context, agentId, null);
    }

    /**
     * @param selectedClients client labels from the UI; when null/empty falls back to
     *                        {@link AllowedClients#orderedEntries()}
     */
    public void Start(BrowserContext context, String agentId, List<String> selectedClients) throws Exception {
	Page page = resolveWorkfilePage(context);
	try {
	    PlaywrightService ps = new PlaywrightService(page);

	    boolean didLogin = zs.login(page);
	    if (didLogin) {
		Thread.sleep(5000);
	    } else {
		logger.info("Skipped Zotec login — moving to Select client(s)");
		Thread.sleep(1000);
	    }

	    openClientSelector(ps, page);
	    List<Locator> clients = ps.getElements(CLIENT_CHECKBOX_XPATH, "getting client checkboxes");
	    int clientCount = clients.size();
	    logger.info("Found {} client checkbox(es) in Select client(s)", clientCount);

	    List<String> uiLabels = new java.util.ArrayList<>(clientCount);
	    for (int i = 0; i < clientCount; i++) {
		uiLabels.add(readClientLabel(clients.get(i)));
	    }

	    List<String> allowlist = (selectedClients != null && !selectedClients.isEmpty())
		    ? selectedClients
		    : AllowedClients.orderedEntries();
	    java.util.Set<Integer> usedUiIndexes = new java.util.HashSet<>();
	    logger.info("Walking {} client entr(y/ies) in order (skip if missing from UI)",
		    allowlist.size());

	    for (int a = 0; a < allowlist.size(); a++) {
		String allowEntry = allowlist.get(a);
		int clientIndex = AllowedClients.findMatchingUiIndex(allowEntry, uiLabels, usedUiIndexes);
		if (clientIndex < 0) {
		    logger.info("Allowlist [{}/{}]: '{}' — not in Select client(s), skipping",
			    a + 1, allowlist.size(), allowEntry);
		    continue;
		}
		usedUiIndexes.add(clientIndex);
		logger.info("Allowlist [{}/{}]: '{}' — matched UI checkbox [{}] '{}'",
			a + 1, allowlist.size(), allowEntry, clientIndex, uiLabels.get(clientIndex));

		String selectedClientLocation = selectOnlyClientAndApply(ps, page, clientIndex);
		logger.info("Selected client_location for upload metadata: {}", selectedClientLocation);

		int patientIndex = 0;
		String previousTextFingerprint = null;

		while (true) {
		    // ONLY leave this location when the empty-queue banner is shown
		    if (hasNoMoreReportsMessage(page)) {
			logger.info("UI: no more reports for allowlist '{}' — next location", allowEntry);
			break;
		    }

		    dismissDataLockedIfPresent(page);

		    patientIndex++;
		    logger.info("--- Patient #{} under '{}' ---", patientIndex, allowEntry);

		    Locator reportLoc = page.locator("//*[@id='dictated-report-text']");
		    String text = waitForDictatedReportText(ps, page, reportLoc);
		    if (hasNoMoreReportsMessage(page)) {
			logger.info("No more reports while waiting for dictated text — next location");
			break;
		    }
		    if (text == null || text.isBlank()) {
			logger.warn("Still no dictated text — retrying on same location (not advancing)");
			Thread.sleep(3000);
			patientIndex--; // don't inflate count on empty waits
			continue;
		    }

		    String fingerprint = textFingerprint(text);
		    if (previousTextFingerprint != null && previousTextFingerprint.equals(fingerprint)) {
			logger.warn(
				"Dictated text unchanged — dismissing Data Locked if any and Skip again (stay on location)");
			dismissDataLockedIfPresent(page);
			SkipAdvanceResult stuck = clickSkipAndWaitForNext(ps, page, previousTextFingerprint);
			if (stuck == SkipAdvanceResult.NO_MORE_REPORTS) {
			    break;
			}
			continue;
		    }
		    previousTextFingerprint = fingerprint;

		    if (hasReportCompletedMessage(page)) {
			logger.info("UI: This report has been completed — Skip to next patient ('{}')",
				allowEntry);
			SkipAdvanceResult completedSkip = clickSkipAndWaitForNext(ps, page,
				previousTextFingerprint);
			if (completedSkip == SkipAdvanceResult.NO_MORE_REPORTS) {
			    break;
			}
			continue;
		    }

		    boolean processed = processOnePatient(ps, page, text, selectedClientLocation);
		    if (!processed) {
			logger.error("Patient #{} failed — waiting for manual Submit/Skip", patientIndex);
		    }

		    SkipAdvanceResult advance = waitForManualSubmitOrSkipAndNext(ps, page,
			    previousTextFingerprint);
		    if (advance == SkipAdvanceResult.NO_MORE_REPORTS) {
			logger.info("No more patients for '{}' — next allowlist location", allowEntry);
			break;
		    }
		    if (advance == SkipAdvanceResult.TIMEOUT) {
			logger.warn(
				"Manual Submit/Skip wait timed out — stay on '{}'; will retry next loop",
				allowEntry);
		    }
		}
	    }

	    logger.info("All AllowedClients locations processed");
	    page.pause();

	} catch (Exception e) {
	    e.printStackTrace();
	    page.pause();
	}
    }

    /**
     * Prefer an existing tab that already shows the Select client(s) toggle;
     * otherwise open a new page for login / navigate.
     */
    private Page resolveWorkfilePage(BrowserContext context) {
	for (Page existing : context.pages()) {
	    try {
		Locator toggle = existing.locator(SELECT_CLIENTS_TOGGLE).first();
		if (toggle.count() > 0 && toggle.isVisible()) {
		    logger.info("Reusing existing tab with Select client(s) toggle visible");
		    existing.bringToFront();
		    return existing;
		}
	    } catch (Exception e) {
		// try next tab
	    }
	}
	return context.newPage();
    }

    /**
     * Opens Select client(s). Prefers a real toggle click (Angular
     * {@code refreshLocationFilter}), then force-opens Bootstrap state if ZTEC
     * blocks the click path: add {@code open} on {@code li.dropdown}, show
     * {@code #myDropdown}. Re-asserts open while waiting for checkboxes.
     */
    private void openClientSelector(PlaywrightService ps, Page page) throws InterruptedException {
	page.bringToFront();
	Thread.sleep(500);

	Locator toggle = page.locator(SELECT_CLIENTS_TOGGLE).first();
	toggle.waitFor(new Locator.WaitForOptions()
		.setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
		.setTimeout(30_000));

	if (isClientDropdownOpen(page)) {
	    logger.info("Select client(s) #myDropdown already open — skipping toggle click");
	} else {
	    boolean opened = false;
	    for (int attempt = 1; attempt <= 3 && !opened; attempt++) {
		logger.info("Clicking Select client(s) dropdown-toggle (attempt {})", attempt);
		toggle.scrollIntoViewIfNeeded();
		try {
		    // Native click so Angular ng-click="refreshLocationFilter()" runs
		    toggle.click(new Locator.ClickOptions().setTimeout(10_000));
		} catch (Exception e) {
		    logger.warn("Normal click failed ({}) — force click", e.getMessage());
		    toggle.click(new Locator.ClickOptions().setForce(true).setTimeout(10_000));
		}

		opened = waitForClientDropdownOpen(page, 3_000);
		if (!opened) {
		    // Fallback: DOM click (still fires Angular handlers on this <a>)
		    Object js = page.evaluate("() => {"
			    + "  const a = document.querySelector(\"a.dropdown-toggle[ng-click*='refreshLocationFilter']\");"
			    + "  if (!a) return 'missing';"
			    + "  a.click();"
			    + "  return 'clicked';"
			    + "}");
		    logger.info("JS toggle click result={} (attempt {})", js, attempt);
		    opened = waitForClientDropdownOpen(page, 2_000);
		}
		if (!opened) {
		    // ZTEC workaround: force Bootstrap open without relying on the click path
		    Object forced = forceOpenClientDropdown(page);
		    logger.info("Force-open Select client(s) result={} (attempt {})", forced, attempt);
		    opened = waitForClientDropdownOpen(page, 3_000);
		}
		if (!opened && attempt < 3) {
		    // Only retry if still closed — never click again while open (would close it)
		    Thread.sleep(1000);
		}
	    }
	    if (!opened) {
		throw new IllegalStateException(
			"Select client(s) dropdown did not open (#myDropdown / li.dropdown.open)");
	    }
	    logger.info("Select client(s) dropdown is open (#myDropdown visible)");
	}

	waitForClientCheckboxesWithForceOpen(ps, page);
	Thread.sleep(500);
    }

    /**
     * Force Bootstrap dropdown open for ZTEC interference: {@code li.dropdown.open},
     * show {@code #myDropdown}, and invoke Angular {@code refreshLocationFilter} when available.
     */
    private Object forceOpenClientDropdown(Page page) {
	return page.evaluate("() => {"
		+ "  const a = document.querySelector(\"a.dropdown-toggle[ng-click*='refreshLocationFilter']\");"
		+ "  if (!a) return 'missing-toggle';"
		+ "  const li = a.closest('li.dropdown');"
		+ "  if (!li) return 'missing-li';"
		+ "  const panel = document.getElementById('myDropdown') || li.querySelector('#myDropdown');"
		+ "  if (!panel) return 'missing-panel';"
		+ "  try {"
		+ "    if (window.angular) {"
		+ "      const el = window.angular.element(a);"
		+ "      const scope = el.scope && el.scope();"
		+ "      if (scope && typeof scope.refreshLocationFilter === 'function') {"
		+ "        if (scope.$apply) {"
		+ "          scope.$apply(function() { scope.refreshLocationFilter(); });"
		+ "        } else {"
		+ "          scope.refreshLocationFilter();"
		+ "        }"
		+ "      }"
		+ "    }"
		+ "  } catch (e) { /* Angular may be unavailable */ }"
		+ "  li.classList.add('open');"
		+ "  a.setAttribute('aria-expanded', 'true');"
		+ "  panel.style.display = 'block';"
		+ "  panel.style.visibility = 'visible';"
		+ "  if (panel.classList) panel.classList.add('open');"
		+ "  return 'forced-open';"
		+ "}");
    }

    /**
     * Wait for client checkboxes; if ZTEC closes the panel, re-force open and retry.
     */
    private void waitForClientCheckboxesWithForceOpen(PlaywrightService ps, Page page)
	    throws InterruptedException {
	for (int attempt = 1; attempt <= 5; attempt++) {
	    if (!isClientDropdownOpen(page)) {
		Object forced = forceOpenClientDropdown(page);
		logger.info("Re-force-open Select client(s) before checkbox wait (attempt {}) result={}",
			attempt, forced);
		Thread.sleep(300);
	    }
	    try {
		ps.waitForElement(page.locator(CLIENT_CHECKBOX_XPATH).first(),
			"waiting for client checkboxes");
		if (clientCheckboxesVisible(page)) {
		    return;
		}
	    } catch (Exception e) {
		logger.warn("Client checkboxes not ready (attempt {}): {}", attempt, e.getMessage());
	    }
	    forceOpenClientDropdown(page);
	    Thread.sleep(500);
	}
	ps.waitForElement(page.locator(CLIENT_CHECKBOX_XPATH).first(), "waiting for client checkboxes");
    }

    private boolean waitForClientDropdownOpen(Page page, double timeoutMs) {
	try {
	    page.locator(CLIENT_DROPDOWN_OPEN).first()
		    .waitFor(new Locator.WaitForOptions()
			    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
			    .setTimeout(timeoutMs));
	    return true;
	} catch (Exception e) {
	    return isClientDropdownOpen(page);
	}
    }

    private boolean isClientDropdownOpen(Page page) {
	try {
	    Locator open = page.locator(CLIENT_DROPDOWN_OPEN).first();
	    if (open.count() > 0 && open.isVisible()) {
		return true;
	    }
	    Locator panel = page.locator("#myDropdown").first();
	    return panel.count() > 0 && panel.isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    private boolean clientCheckboxesVisible(Page page) {
	try {
	    Locator first = page.locator(CLIENT_CHECKBOX_XPATH).first();
	    return first.count() > 0 && first.isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    /**
     * Label text + checkbox id for allowlist matching (name and/or code in parentheses).
     */
    private String readClientLabel(Locator checkbox) {
	StringBuilder sb = new StringBuilder();
	try {
	    String id = checkbox.getAttribute("id");
	    if (id != null && !id.isBlank()) {
		sb.append(id).append(' ');
	    }
	} catch (Exception ignored) {
	}
	try {
	    Locator label = checkbox.locator("xpath=ancestor::label[1]");
	    if (label.count() > 0) {
		sb.append(label.first().innerText());
	    } else {
		sb.append(checkbox.locator("xpath=..").innerText());
	    }
	} catch (Exception e) {
	    logger.warn("Could not read client label: {}", e.getMessage());
	}
	return sb.toString().trim();
    }

    /**
     * Uncheck all client boxes, check only {@code clientIndex}, then APPLY.
     * Re-queries locators each time (DOM refreshes after APPLY).
     *
     * @return Select client(s) display name for upload {@code client_location}
     */
    private String selectOnlyClientAndApply(PlaywrightService ps, Page page, int clientIndex)
	    throws InterruptedException {
	openClientSelector(ps, page);

	List<Locator> clients = ps.getElements(CLIENT_CHECKBOX_XPATH, "refresh client checkboxes");
	if (clientIndex < 0 || clientIndex >= clients.size()) {
	    throw new IllegalStateException("Client index out of range: " + clientIndex + " (size="
		    + clients.size() + ")");
	}

	for (int i = 0; i < clients.size(); i++) {
	    Locator box = clients.get(i);
	    try {
		if (box.isChecked()) {
		    box.click();
		    Thread.sleep(200);
		}
	    } catch (Exception e) {
		logger.warn("Could not uncheck client checkbox {}: {}", i, e.getMessage());
	    }
	}

	Locator selected = clients.get(clientIndex);
	String clientLocation = WorkfileSummaryScraper.readSelectedClientDisplayName(selected);
	selected.click();
	Thread.sleep(2000);
	ps.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("APPLY")), "APPLY client");
	Thread.sleep(3000);
	return clientLocation;
    }

    /**
     * Upload dictated text (with well + client_location metadata) and fill form.
     */
    private boolean processOnePatient(PlaywrightService ps, Page page, String text,
	    String selectedClientLocation) throws Exception {
	if (hasReportCompletedMessage(page)) {
	    logger.info("This report has been completed — skipping fill");
	    return true;
	}

	if (dismissDataLockedIfPresent(page)) {
	    logger.info("Data Locked dismissed at start of patient — treat as skip to next");
	    return true;
	}

	Map<String, Object> uploadMetadata = WorkfileSummaryScraper.build(page, selectedClientLocation);
	Map<String, Object> uploadMeta = documentProcessing.uploadTextAndAwaitResume(text, uploadMetadata);

	@SuppressWarnings("unchecked")
	Map<String, Object> resumePayload = uploadMeta.get("resume_payload") instanceof Map
		? (Map<String, Object>) uploadMeta.get("resume_payload")
		: Map.of();

	if (resumePayload.isEmpty()) {
	    logger.error("No resume payload received (document_id={}, errors={})",
		    uploadMeta.get("document_id"), uploadMeta.get("resume_error"));
	    return false;
	}

	patientInfo = ResumePayloadMapper.toValidationMap(resumePayload);
	patientInfo.put("document_id", uploadMeta.get("document_id"));
	patientInfo.put("batch_id", uploadMeta.get("document_id"));
	patientInfo.put("text_path", uploadMeta.get("text_path"));
	patientInfo.put("resume_payload", resumePayload);

	logger.info("Resume payload received for document_id={}", uploadMeta.get("document_id"));

	zs.validatePatientDetails(page, patientInfo);

	if (dismissDataLockedIfPresent(page)) {
	    logger.info("Data Locked after patient details — OK clicked, move to next patient");
	    return true;
	}

	Service s = new Service();
	List<Map<String, Object>> cptEntries = ResumePayloadMapper.extractCptEntries(resumePayload);
	List<String> icdList = ResumePayloadMapper.extractIcdCodeList(resumePayload);

	logger.info("validateCPT entries: {}", cptEntries);
	logger.info("validateICD codes: {}", icdList);

	// Order: ED → clear-all CPT + fill from JSON → ICD → billing extras → Issue/RFI
	ps.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("ED")), "clicking ED");
	Thread.sleep(2000);
	try {
	    page.locator("#codingAssistantBody #autoCoderForm, #autoCoderForm").first()
		    .waitFor(new Locator.WaitForOptions()
			    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
			    .setTimeout(15_000));
	} catch (Exception e) {
	    logger.warn("ED form did not appear: {}", e.getMessage());
	}

	if (dismissDataLockedIfPresent(page)) {
	    logger.info("Data Locked on ED — OK clicked, move to next patient");
	    return true;
	}

	if (ED_EMFormPlaywrightApplier.isCriticalCareFormVisible(page)
		|| ResumePayloadMapper.shouldUseCriticalCareForm(resumePayload)) {
	    logger.info("Applying Critical Care ED form from resume payload");
	} else {
	    logger.info("Applying EM Level ED form from resume payload");
	}
	ED_EMFormPlaywrightApplier.applyEdFormFromResume(page, resumePayload);

	ps.click(page.locator("input[type=\"submit\"]"), "ed submit");

	Thread.sleep(2000);

	if (dismissDataLockedIfPresent(page)) {
	    logger.info("Data Locked after ED submit — OK clicked, move to next patient");
	    return true;
	}

	s.validateCPT(page, cptEntries, icdList);

	if (dismissDataLockedIfPresent(page)) {
	    logger.info("Data Locked after CPT — OK clicked, move to next patient");
	    return true;
	}

	s.validateICD(icdList, page);
	new CodingFormValidationService(page).updateBillingExtras(patientInfo);
	IssueOrRfiApplier.applyAfterCodingFill(page, resumePayload);

	if (dismissDataLockedIfPresent(page)) {
	    logger.info("Data Locked after ICD/billing — OK clicked, move to next patient");
	}
	return true;
    }

    private enum SkipAdvanceResult {
	NEXT_PATIENT, NO_MORE_REPORTS, TIMEOUT
    }

    /**
     * Wait for dictated report text. Does not advance checkbox — only returns when text is ready,
     * "no more reports" is shown, or a long poll expires (caller stays on same checkbox).
     * Polls quietly (no full-text logs); logs length once when ready.
     */
    private String waitForDictatedReportText(PlaywrightService ps, Page page, Locator reportLoc)
	    throws InterruptedException {
	for (int attempt = 0; attempt < 120; attempt++) {
	    if (hasNoMoreReportsMessage(page)) {
		return null;
	    }
	    dismissDataLockedIfPresent(page);
	    String text = readDictatedReportTextQuiet(reportLoc);
	    if (text != null && !text.isBlank()) {
		logger.info("Dictated report ready ({} chars)", text.length());
		return text;
	    }
	    Thread.sleep(1000);
	}
	logger.warn("Timed out waiting for dictated report text (staying on same checkbox)");
	return null;
    }

    /**
     * Do not click Submit/Skip — wait until the user clicks either button manually.
     * Detects advance when dictated report text changes or the empty-queue banner appears.
     */
    private SkipAdvanceResult waitForManualSubmitOrSkipAndNext(PlaywrightService ps, Page page,
	    String previousFingerprint) throws InterruptedException {
	if (hasNoMoreReportsMessage(page)) {
	    return SkipAdvanceResult.NO_MORE_REPORTS;
	}

	dismissDataLockedIfPresent(page);

	logger.info(
		"Waiting for USER to click Submit or Skip manually — bot will not click either button");

	Locator reportLoc = page.locator("//*[@id='dictated-report-text']");
	// Up to ~60 minutes for manual review
	for (int attempt = 0; attempt < 3600; attempt++) {
	    if (dismissDataLockedIfPresent(page)) {
		logger.info("Data Locked while waiting for manual Submit/Skip — OK clicked; continue waiting");
		Thread.sleep(2000);
		continue;
	    }
	    if (hasNoMoreReportsMessage(page)) {
		logger.info("No more reports after manual Submit/Skip");
		return SkipAdvanceResult.NO_MORE_REPORTS;
	    }
	    try {
		String fp = dictatedTextFingerprint(reportLoc);
		if (fp != null && !fp.isBlank()
			&& (previousFingerprint == null || !fp.equals(previousFingerprint))) {
		    logger.info("Next patient detected after manual Submit/Skip (text fingerprint changed)");
		    return SkipAdvanceResult.NEXT_PATIENT;
		}
	    } catch (Exception ignored) {
	    }
	    if (attempt > 0 && attempt % 30 == 0) {
		logger.info("Still waiting for manual Submit/Skip... ({}s)", attempt);
	    }
	    Thread.sleep(1000);
	}

	logger.warn("Timed out waiting for manual Submit/Skip — stay on checkbox (do not advance)");
	return SkipAdvanceResult.TIMEOUT;
    }

    /**
     * Click bottom Skip and wait for the next patient report.
     * {@link SkipAdvanceResult#NO_MORE_REPORTS} is the only signal to leave the checkbox.
     * Timeout / Data Locked → dismiss OK and keep trying; do not advance checkbox.
     */
    private SkipAdvanceResult clickSkipAndWaitForNext(PlaywrightService ps, Page page, String previousFingerprint)
	    throws InterruptedException {
	if (hasNoMoreReportsMessage(page)) {
	    return SkipAdvanceResult.NO_MORE_REPORTS;
	}

	dismissDataLockedIfPresent(page);

	Locator skipBtn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Skip"));
	if (skipBtn.count() == 0 || !skipBtn.first().isEnabled()) {
	    if (hasNoMoreReportsMessage(page)) {
		return SkipAdvanceResult.NO_MORE_REPORTS;
	    }
	    logger.info("Skip button missing or disabled — waiting (not leaving checkbox)");
	    Thread.sleep(3000);
	    dismissDataLockedIfPresent(page);
	    return SkipAdvanceResult.TIMEOUT;
	}

	ps.click(skipBtn.first(), "Skip → next patient");
	Thread.sleep(3000);

	Locator reportLoc = page.locator("//*[@id='dictated-report-text']");
	for (int attempt = 0; attempt < 60; attempt++) {
	    if (dismissDataLockedIfPresent(page)) {
		logger.info("Data Locked after Skip — OK clicked; waiting for page refresh / next patient");
		Thread.sleep(2000);
		continue;
	    }
	    if (hasNoMoreReportsMessage(page)) {
		logger.info("No more reports after Skip");
		return SkipAdvanceResult.NO_MORE_REPORTS;
	    }
	    try {
		String fp = dictatedTextFingerprint(reportLoc);
		if (fp != null && !fp.isBlank() && !fp.equals(previousFingerprint)) {
		    logger.info("Next patient detected after Skip (text fingerprint changed)");
		    return SkipAdvanceResult.NEXT_PATIENT;
		}
	    } catch (Exception ignored) {
	    }
	    Thread.sleep(1000);
	}

	logger.warn("Timed out waiting for next patient after Skip — stay on checkbox (do not advance)");
	return SkipAdvanceResult.TIMEOUT;
    }

    /**
     * If the "Data Locked" dialog is visible, click OK (page refreshes). Returns true if dismissed.
     */
    private boolean dismissDataLockedIfPresent(Page page) {
	try {
	    Locator body = page.getByText(DATA_LOCKED_BODY);
	    Locator title = page.getByText(DATA_LOCKED_TITLE);
	    boolean visible = (body.count() > 0 && body.first().isVisible())
		    || (title.count() > 0 && title.first().isVisible());
	    if (!visible) {
		return false;
	    }
	    logger.info("Data Locked dialog detected — clicking OK");
	    Locator ok = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("OK"));
	    if (ok.count() == 0) {
		ok = page.locator("button:has-text('OK'), .modal button.btn-primary").first();
	    }
	    if (ok.count() > 0) {
		ok.first().click(new Locator.ClickOptions().setForce(true));
	    } else {
		page.keyboard().press("Escape");
	    }
	    Thread.sleep(2000);
	    return true;
	} catch (Exception e) {
	    logger.warn("dismissDataLockedIfPresent: {}", e.getMessage());
	    return false;
	}
    }

    /** True when the empty-queue banner is visible. */
    private boolean hasNoMoreReportsMessage(Page page) {
	try {
	    Locator banner = page.getByText(NO_MORE_REPORTS);
	    return banner.count() > 0 && banner.first().isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    /** True when the current report is already completed in the UI. */
    private boolean hasReportCompletedMessage(Page page) {
	try {
	    Locator banner = page.getByText(REPORT_COMPLETED);
	    return banner.count() > 0 && banner.first().isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    private static String textFingerprint(String text) {
	if (text == null) {
	    return "";
	}
	String t = text.trim();
	return t.substring(0, Math.min(200, t.length()));
    }

    /**
     * First 200 chars of dictated text — used like Flow's image fingerprint.
     * Does not log the report body.
     */
    private static String dictatedTextFingerprint(Locator reportLoc) {
	return textFingerprint(readDictatedReportTextQuiet(reportLoc));
    }

    /** innerText of {@code #dictated-report-text} with no PlaywrightService dump. */
    private static String readDictatedReportTextQuiet(Locator reportLoc) {
	try {
	    if (reportLoc.count() == 0 || !reportLoc.first().isVisible()) {
		return null;
	    }
	    String text = reportLoc.first().innerText();
	    if (text == null || text.isBlank()) {
		return null;
	    }
	    return text;
	} catch (Exception e) {
	    return null;
	}
    }

    static void saveFile() throws IOException, InterruptedException {
	String[] headers = { "Processed Date", "Provider Name", "Account Number", "Patient Name", "DOS", "Work Status",
		"Exception Reason", "Eligibility Cheked", "Eligibility Status", "CPT", "Duration", "Units Created",
		"Patient Balance Amount $", "Payment Link Sent" };

	FileUtil.create("MetroOutNew", headers);
	System.out.println(ExcelObj);
	FileUtil.addRow(ExcelObj, "MetroOutNew");
	FileUtil.PrintJson(ExcelObj, ExcelObj.get("Patient Name") + "_" + ExcelObj.get("DOS"));
	Thread.sleep(1000);
    }

    static void createOrder() {
	ExcelObj.put("Processed Date", "");
	ExcelObj.put("Provider Name", "");
	ExcelObj.put("Account Number", "");
	ExcelObj.put("Patient Name", "");
	ExcelObj.put("DOS", "");
	ExcelObj.put("Work Status", "");
	ExcelObj.put("Exception Reason", "");
	ExcelObj.put("Eligibility Cheked", "");
	ExcelObj.put("Eligibility Status", "");
	ExcelObj.put("CPT", "");
	ExcelObj.put("Duration", "");
	ExcelObj.put("Units Created", "");
	ExcelObj.put("Patient Balance Amount $", "");
	ExcelObj.put("Payment Link Sent", "");
    }

    static void loadOff(Page page) {
	while (true) {
	    try {
		String display = page.locator("#LoadingPanelAction").getAttribute("style");
		System.out.println(" =========== " + display);
		if (display.contains("none")) {
		    System.out.println("loaded ");
		    Thread.sleep(1000);
		    break;
		}
	    } catch (Exception e) {
		System.out.println("error");
	    }
	    try {
		Thread.sleep(1000);
	    } catch (InterruptedException e) {
		e.printStackTrace();
	    }
	}
    }
}
