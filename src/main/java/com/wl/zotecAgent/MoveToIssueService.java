package com.wl.zotecAgent;

import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Opens "Move to Issue" and fills Issue Type / Issue Comment when
 * {@code issue} (or legacy {@code move_to_issue}) is present with data in the resume JSON.
 */
public class MoveToIssueService {

    private static final Logger log = LogManager.getLogger(MoveToIssueService.class);

    private static final String ISSUE_TYPE_CONTAINER = "#s2id_codingissuetype";
    private static final String ISSUE_TYPE_CHOICE = "#s2id_codingissuetype .select2-choice";
    private static final String ISSUE_TYPE_CHOSEN = "#s2id_codingissuetype .select2-chosen";
    private static final String ISSUE_COMMENT =
	    "div[ng-controller='Coding.Form.IssueController'] textarea[name='comment'], "
		    + "div[ng-form='issueForm'] textarea[name='comment']";

    private final Page page;

    public MoveToIssueService(Page page) {
	this.page = page;
    }

    /**
     * If JSON has usable {@code issue} / {@code move_to_issue} data: click Move to Issue,
     * fill Issue Type and Issue Comment. Otherwise skip.
     */
    public void applyFromResume(Map<String, Object> resumePayload) {
	Map<String, String> issue = ResumePayloadMapper.extractMoveToIssue(resumePayload);
	if (issue == null || issue.isEmpty()) {
	    PlayTestActionLog.skip("Move to Issue", "issue / move_to_issue missing or empty in JSON");
	    log.info("Move to Issue skipped — no issue data in JSON");
	    return;
	}

	String issueType = blankToNull(issue.get("issue_type"));
	String comment = blankToNull(issue.get("comment"));
	if (issueType == null && comment == null) {
	    PlayTestActionLog.skip("Move to Issue", "issue has no issue_type or issue_comment");
	    log.info("Move to Issue skipped — empty issue_type and comment");
	    return;
	}

	PlayTestActionLog.step("Move to Issue — type='" + issueType + "' comment present=" + (comment != null));
	try {
	    if (!ensureIssueFormOpen()) {
		PlayTestActionLog.skip("Move to Issue", "could not open issue form");
		return;
	    }
	    if (issueType != null) {
		fillIssueType(issueType);
	    }
	    if (comment != null) {
		fillIssueComment(comment);
	    }
	    PlayTestActionLog.update("Move to Issue", "filled from JSON");
	} catch (Exception e) {
	    log.warn("Move to Issue failed (continuing): {}", e.getMessage(), e);
	    PlayTestActionLog.skip("Move to Issue", e.getMessage());
	}
    }

    private boolean ensureIssueFormOpen() throws InterruptedException {
	if (isIssueFormVisible()) {
	    log.info("Issue form already open");
	    return true;
	}

	ZtecVerifierGate.dismissYesIDidIfPresent(page);

	Locator btn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Move to Issue"));
	if (btn.count() == 0 || !btn.first().isVisible()) {
	    btn = page.locator("button:has-text('Move to Issue')");
	}
	if (btn.count() == 0 || !btn.first().isVisible()) {
	    log.warn("Move to Issue button not found or not visible");
	    return false;
	}

	boolean disabled = Boolean.TRUE.equals(btn.first().isDisabled());
	if (disabled) {
	    log.warn("Move to Issue is disabled (canAddIssue=false). "
		    + "Will try Angular addIssue() — usually needs CPT/ICD filled first.");
	    PlayTestActionLog.update("Move to Issue", "button disabled — invoking addIssue() via Angular");
	} else {
	    PlayTestActionLog.update("Move to Issue", "clicking button");
	}

	btn.first().scrollIntoViewIfNeeded();
	if (!disabled) {
	    try {
		btn.first().click(new Locator.ClickOptions().setTimeout(5_000));
	    } catch (Exception e) {
		log.warn("Normal click failed ({}), force + Angular fallback", e.getMessage());
		btn.first().click(new Locator.ClickOptions().setForce(true));
	    }
	}
	invokeAngularAddIssue();

	for (int i = 0; i < 24; i++) {
	    if (isIssueFormVisible()) {
		log.info("Issue form opened after Move to Issue");
		return true;
	    }
	    Thread.sleep(250);
	}
	log.warn("Issue form did not appear after clicking Move to Issue"
		+ (disabled ? " (button was disabled — fill CPT/ICD first so canAddIssue() is true)" : ""));
	return false;
    }

    /**
     * Calls Angular {@code addIssue()} on the button / parent scopes. Needed when the
     * button is {@code ng-disabled} — Playwright force-click does not fire ng-click.
     */
    private void invokeAngularAddIssue() {
	try {
	    Object result = page.evaluate("""
		    () => {
		      const buttons = Array.from(document.querySelectorAll('button'));
		      const btn = buttons.find(b => (b.textContent || '').trim() === 'Move to Issue');
		      if (!btn) return 'no-button';
		      const angular = window.angular;
		      if (!angular || !angular.element) {
		        btn.click();
		        return 'dom-click-no-angular';
		      }
		      let el = angular.element(btn);
		      let scope = el.scope && el.scope();
		      for (let i = 0; i < 8 && scope; i++) {
		        if (typeof scope.addIssue === 'function') {
		          scope.$applyAsync(() => scope.addIssue());
		          try { scope.$apply(); } catch (e) {}
		          return 'addIssue';
		        }
		        scope = scope.$parent;
		      }
		      // Also try controller on form root
		      const form = document.querySelector("form[name='form']");
		      if (form) {
		        let fs = angular.element(form).scope();
		        for (let i = 0; i < 10 && fs; i++) {
		          if (typeof fs.addIssue === 'function') {
		            fs.$applyAsync(() => fs.addIssue());
		            try { fs.$apply(); } catch (e) {}
		            return 'addIssue-form';
		          }
		          fs = fs.$parent;
		        }
		      }
		      btn.click();
		      return 'dom-click-fallback';
		    }
		    """);
	    log.info("Angular addIssue invoke result: {}", result);
	} catch (Exception e) {
	    log.warn("Angular addIssue invoke failed: {}", e.getMessage());
	}
    }

    private boolean isIssueFormVisible() {
	try {
	    // Prefer the IssueController well / Issue Type control — #issue-well is only an empty <a>
	    Locator ctrl = page.locator("div[ng-controller='Coding.Form.IssueController']").first();
	    if (ctrl.count() > 0 && ctrl.isVisible()) {
		return true;
	    }
	    Locator type = page.locator("#s2id_codingissuetype, #codingissuetype, select[name='typeId']").first();
	    if (type.count() > 0 && type.isVisible()) {
		return true;
	    }
	    Locator label = page.locator("label.control-label").filter(
		    new Locator.FilterOptions().setHasText("Issue Type"));
	    return label.count() > 0 && label.first().isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    private void fillIssueType(String issueType) throws InterruptedException {
	dismissSelect2();

	Locator scope = page.locator("div[ng-controller='Coding.Form.IssueController']").first();
	// Wait for Issue Type control — ng-include can lag behind the well appearing
	Locator choice = null;
	for (int i = 0; i < 20; i++) {
	    choice = resolveIssueTypeChoice(scope);
	    if (choice != null) {
		break;
	    }
	    Locator nativeSelect = page.locator("#codingissuetype, div[ng-controller='Coding.Form.IssueController'] select[name='typeId']")
		    .first();
	    if (nativeSelect.count() > 0) {
		break;
	    }
	    Thread.sleep(150);
	}

	if (choice != null) {
	    try {
		choice.scrollIntoViewIfNeeded();
	    } catch (Exception ignored) {
	    }
	}

	String current = "";
	try {
	    Locator chosen = page.locator(ISSUE_TYPE_CHOSEN).first();
	    if (chosen.count() > 0) {
		current = chosen.innerText().trim();
	    }
	} catch (Exception ignored) {
	}
	if (!current.isBlank() && issueTypeMatches(current, issueType)) {
	    PlayTestActionLog.skip("Issue Type", current);
	    return;
	}

	PlayTestActionLog.update("Issue Type", "'" + current + "' -> '" + issueType + "'");

	// Prefer native <select> — options are already in DOM (e.g. "Addendum Recieved")
	if (selectIssueTypeFromNativeSelect(issueType)) {
	    return;
	}

	if (choice == null) {
	    PlayTestActionLog.skip("Issue Type", "select2/native control not on form");
	    return;
	}

	choice.click(new Locator.ClickOptions().setForce(true));
	Thread.sleep(300);

	Locator search = page.locator(
		".select2-drop-active .select2-search input.select2-input, "
			+ ".select2-drop:not(.select2-display-none) .select2-search input.select2-input")
		.first();
	if (search.count() > 0 && search.isVisible()) {
	    search.fill("");
	    search.fill(issueType);
	    Thread.sleep(400);
	}

	Locator results = page.locator(
		".select2-drop-active .select2-results li.select2-result-selectable, "
			+ ".select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable");
	for (int i = 0; i < 8 && results.count() == 0; i++) {
	    Thread.sleep(200);
	}

	Locator match = findBestIssueTypeResult(results, issueType);
	if (match == null) {
	    dismissSelect2();
	    if (selectIssueTypeFromNativeSelect(issueType)) {
		return;
	    }
	    PlayTestActionLog.skip("Issue Type", "no matching option for '" + issueType + "'");
	    return;
	}
	match.click(new Locator.ClickOptions().setForce(true));
	Thread.sleep(300);
	dismissSelect2();
    }

    private Locator resolveIssueTypeChoice(Locator scope) {
	try {
	    Locator byId = page.locator(ISSUE_TYPE_CHOICE).first();
	    if (byId.count() > 0) {
		try {
		    byId.scrollIntoViewIfNeeded();
		} catch (Exception ignored) {
		}
		if (byId.isVisible()) {
		    return byId;
		}
		// Present but not "visible" (scrollable well) — still usable with force click
		if (byId.count() > 0) {
		    return byId;
		}
	    }
	} catch (Exception ignored) {
	}
	try {
	    if (scope != null && scope.count() > 0) {
		Locator byLabel = scope.locator(
			"xpath=.//label[normalize-space()='Issue Type']/following::a[contains(@class,'select2-choice')][1]")
			.first();
		if (byLabel.count() > 0) {
		    return byLabel;
		}
		Locator any = scope.locator("a.select2-choice").first();
		if (any.count() > 0) {
		    return any;
		}
	    }
	} catch (Exception ignored) {
	}
	return null;
    }

    private Locator findBestIssueTypeResult(Locator results, String issueType) {
	if (results == null || results.count() == 0) {
	    return null;
	}
	String want = issueType.trim().toLowerCase(Locale.ROOT);
	Locator exact = null;
	Locator starts = null;
	Locator contains = null;
	for (int i = 0; i < results.count(); i++) {
	    String text = results.nth(i).innerText().trim();
	    String lower = text.toLowerCase(Locale.ROOT);
	    if (lower.equals(want)) {
		exact = results.nth(i);
		break;
	    }
	    if (starts == null && lower.startsWith(want)) {
		starts = results.nth(i);
	    }
	    if (contains == null && lower.contains(want)) {
		contains = results.nth(i);
	    }
	}
	return exact != null ? exact : (starts != null ? starts : contains);
    }

    private static boolean issueTypeMatches(String current, String expected) {
	if (current == null || expected == null) {
	    return false;
	}
	return current.trim().equalsIgnoreCase(expected.trim());
    }

    private boolean selectIssueTypeFromNativeSelect(String issueType) {
	try {
	    Locator select = page.locator(
		    "div[ng-controller='Coding.Form.IssueController'] select#codingissuetype, "
			    + "div[ng-controller='Coding.Form.IssueController'] select[name='typeId'], "
			    + "#codingissuetype, select[name='typeId']")
		    .first();
	    if (select.count() == 0) {
		return false;
	    }
	    try {
		select.scrollIntoViewIfNeeded();
	    } catch (Exception ignored) {
	    }
	    Locator options = select.locator("option");
	    String want = issueType.trim().toLowerCase(Locale.ROOT);
	    String exactValue = null;
	    String exactLabel = null;
	    String fuzzyValue = null;
	    String fuzzyLabel = null;
	    for (int i = 0; i < options.count(); i++) {
		String text = options.nth(i).innerText().trim();
		String value = options.nth(i).getAttribute("value");
		if (text.isBlank()) {
		    continue;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		if (lower.equals(want)) {
		    exactValue = value;
		    exactLabel = text;
		    break;
		}
		if (fuzzyLabel == null && (lower.startsWith(want) || lower.contains(want))) {
		    fuzzyValue = value;
		    fuzzyLabel = text;
		}
	    }
	    String value = exactValue != null ? exactValue : fuzzyValue;
	    String label = exactLabel != null ? exactLabel : fuzzyLabel;
	    if (label == null) {
		return false;
	    }
	    if (value != null && !value.isBlank()) {
		select.selectOption(value);
	    } else {
		select.selectOption(new com.microsoft.playwright.options.SelectOption().setLabel(label));
	    }
	    // Notify Angular / Select2 of the change
	    try {
		select.evaluate("el => {"
			+ " el.dispatchEvent(new Event('change', { bubbles: true }));"
			+ " el.dispatchEvent(new Event('input', { bubbles: true }));"
			+ " if (window.angular) {"
			+ "   const s = angular.element(el).scope();"
			+ "   if (s) { try { s.$apply(); } catch (e) {} }"
			+ " }"
			+ "}");
	    } catch (Exception ignored) {
	    }
	    log.info("Issue Type set via native select to '{}'", label);
	    PlayTestActionLog.update("Issue Type", "native select -> '" + label + "'");
	    return true;
	} catch (Exception e) {
	    log.warn("Native Issue Type select failed: {}", e.getMessage());
	}
	return false;
    }

    private void fillIssueComment(String comment) {
	Locator area = page.locator(ISSUE_COMMENT).first();
	if (area.count() == 0 || !area.isVisible()) {
	    PlayTestActionLog.skip("Issue Comment", "textarea not on form");
	    return;
	}
	String current = "";
	try {
	    current = area.inputValue();
	} catch (Exception ignored) {
	}
	if (comment.equals(current)) {
	    PlayTestActionLog.skip("Issue Comment", current);
	    return;
	}
	PlayTestActionLog.update("Issue Comment", "set from JSON");
	area.click(new Locator.ClickOptions().setForce(true));
	area.fill("");
	area.fill(comment);
	try {
	    area.evaluate("el => { el.dispatchEvent(new Event('input', { bubbles: true })); "
		    + "el.dispatchEvent(new Event('change', { bubbles: true })); }");
	} catch (Exception ignored) {
	}
    }

    private void dismissSelect2() {
	try {
	    page.keyboard().press("Escape");
	    Thread.sleep(100);
	} catch (Exception ignored) {
	}
    }

    private static String blankToNull(String s) {
	if (s == null || s.isBlank() || "null".equalsIgnoreCase(s.trim())) {
	    return null;
	}
	return s.trim();
    }
}
