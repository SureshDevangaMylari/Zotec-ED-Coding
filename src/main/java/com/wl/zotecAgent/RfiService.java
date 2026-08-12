package com.wl.zotecAgent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Opens "RFI" and fills RFI Provider / Procedure / Reasons / Comment when
 * {@code rfi} (or {@code RFI}) is present with data in the resume JSON.
 */
public class RfiService {

    private static final Logger log = LogManager.getLogger(RfiService.class);

    private static final String RFI_WELL = "#rfi-well, div[ng-controller='Coding.Form.RFIController']";
    private static final String RFI_FORM = "div[ng-controller='Coding.Form.RFIController']";
    private static final String PROCEDURE_SELECT = RFI_FORM + " select[name='procedureCode']";
    private static final String REASONS_SELECT = RFI_FORM + " select[name='reasonIds']";
    private static final String PROVIDER_INPUT = RFI_FORM + " input[name='provider']";
    private static final String COMMENT = RFI_FORM + " textarea[name='comment']";

    private final Page page;

    public RfiService(Page page) {
	this.page = page;
    }

    /**
     * If JSON has usable RFI data: click RFI, fill fields. Otherwise skip.
     */
    public void applyFromResume(Map<String, Object> resumePayload) {
	Map<String, Object> rfi = ResumePayloadMapper.extractRfi(resumePayload);
	if (rfi == null || rfi.isEmpty()) {
	    PlayTestActionLog.skip("RFI", "rfi missing or empty in JSON");
	    log.info("RFI skipped — no rfi data in JSON");
	    return;
	}

	String provider = str(rfi, "provider");
	String procedure = str(rfi, "procedure");
	@SuppressWarnings("unchecked")
	List<String> reasons = rfi.get("reasons") instanceof List<?>
		? (List<String>) rfi.get("reasons")
		: List.of();
	String comment = str(rfi, "comment");

	if (provider == null && procedure == null && reasons.isEmpty() && comment == null) {
	    PlayTestActionLog.skip("RFI", "rfi has no provider/procedure/reasons/comment");
	    return;
	}

	PlayTestActionLog.step("RFI — provider='" + provider + "' procedure='" + procedure
		+ "' reasons=" + reasons + " comment=" + (comment != null));
	try {
	    if (!ensureRfiFormOpen()) {
		PlayTestActionLog.skip("RFI", "could not open RFI form");
		return;
	    }
	    scrollRfiIntoView();
	    if (provider != null) {
		fillRfiProvider(provider);
	    }
	    if (procedure != null) {
		fillProcedure(procedure);
	    }
	    if (!reasons.isEmpty()) {
		fillReasons(reasons);
	    }
	    if (comment != null) {
		fillComment(comment);
	    }
	    PlayTestActionLog.update("RFI", "filled from JSON");
	} catch (Exception e) {
	    log.warn("RFI failed (continuing): {}", e.getMessage(), e);
	    PlayTestActionLog.skip("RFI", e.getMessage());
	}
    }

    private boolean ensureRfiFormOpen() throws InterruptedException {
	if (isRfiFormVisible()) {
	    log.info("RFI form already open");
	    return true;
	}

	Locator btn = page.getByRole(AriaRole.BUTTON,
		new Page.GetByRoleOptions().setName("RFI").setExact(true));
	if (btn.count() == 0 || !btn.first().isVisible()) {
	    btn = page.locator("button").filter(new Locator.FilterOptions().setHasText("RFI"))
		    .filter(new Locator.FilterOptions().setHasNotText("Resolve"));
	}
	if (btn.count() == 0 || !btn.first().isVisible()) {
	    btn = page.locator(".btn-toolbar button.btn-default")
		    .filter(new Locator.FilterOptions().setHasText("RFI"))
		    .filter(new Locator.FilterOptions().setHasNotText("Resolve"));
	}
	if (btn.count() == 0 || !btn.first().isVisible()) {
	    log.warn("RFI button not found or not visible (may be disabled by Move to Issue)");
	    return false;
	}
	if (Boolean.TRUE.equals(btn.first().isDisabled())) {
	    log.warn("RFI button is disabled");
	    PlayTestActionLog.skip("RFI", "button disabled");
	    return false;
	}

	PlayTestActionLog.update("RFI", "clicking button");
	btn.first().scrollIntoViewIfNeeded();
	btn.first().click(new Locator.ClickOptions().setForce(true));
	Thread.sleep(500);

	for (int i = 0; i < 20; i++) {
	    if (isRfiFormVisible()) {
		log.info("RFI form opened after RFI click");
		return true;
	    }
	    Thread.sleep(250);
	}
	log.warn("RFI form did not appear after clicking RFI");
	return false;
    }

    private boolean isRfiFormVisible() {
	try {
	    Locator well = page.locator(RFI_WELL).first();
	    return well.count() > 0 && well.isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    private void scrollRfiIntoView() {
	try {
	    Locator well = page.locator(RFI_FORM).first();
	    if (well.count() > 0) {
		well.scrollIntoViewIfNeeded();
		Thread.sleep(200);
	    }
	} catch (Exception ignored) {
	}
    }

    private void fillRfiProvider(String provider) throws InterruptedException {
	dismissSelect2();
	Locator scope = page.locator(RFI_FORM).first();
	Locator choice = scope.locator(
		"xpath=.//label[normalize-space()='RFI Provider']/following::a[contains(@class,'select2-choice')][1]")
		.first();
	if (choice.count() == 0) {
	    choice = scope.locator("input[name='provider']")
		    .locator("xpath=preceding-sibling::div[contains(@class,'select2-container')][1]//a[contains(@class,'select2-choice')]")
		    .first();
	}
	if (choice.count() == 0) {
	    choice = scope.locator("a.select2-choice").first();
	}
	if (choice.count() == 0) {
	    PlayTestActionLog.skip("RFI Provider", "select2 not on form");
	    return;
	}

	String current = "";
	try {
	    current = choice.locator(".select2-chosen").innerText().trim();
	} catch (Exception ignored) {
	}
	if (!current.isBlank() && providerNamesMatch(current, provider)) {
	    PlayTestActionLog.skip("RFI Provider", current);
	    return;
	}

	PlayTestActionLog.update("RFI Provider", "-> '" + provider + "'");
	try {
	    choice.scrollIntoViewIfNeeded();
	} catch (Exception ignored) {
	}

	if (openSelect2AndPick(choice, provider, "RFI Provider")) {
	    return;
	}

	// Focusser + keyboard (select2 often filters without a visible search box)
	if (typeViaFocusser(scope, provider, "RFI Provider")) {
	    return;
	}

	if (openSelect2ViaJquery(PROVIDER_INPUT) && typeAndPickSelect2(provider, "RFI Provider")) {
	    return;
	}

	PlayTestActionLog.skip("RFI Provider", "no matching option for '" + provider + "'");
    }

    private boolean openSelect2AndPick(Locator choice, String query, String desc)
	    throws InterruptedException {
	try {
	    choice.click(new Locator.ClickOptions().setTimeout(3_000));
	} catch (Exception e) {
	    choice.click(new Locator.ClickOptions().setForce(true));
	}
	Thread.sleep(400);

	Locator search = waitForSelect2Search(12);
	if (search == null) {
	    // Drop may be nested under the choice container
	    Locator container = choice.locator("xpath=ancestor::div[contains(@class,'select2-container')][1]");
	    if (container.count() > 0) {
		Locator nested = container.locator(".select2-search input.select2-input, input.select2-input")
			.first();
		if (nested.count() > 0) {
		    search = nested;
		}
	    }
	}
	if (search == null) {
	    log.warn("{}: select2 search not visible after click", desc);
	    return false;
	}
	try {
	    search.click(new Locator.ClickOptions().setForce(true));
	    search.fill("");
	    search.fill(query);
	} catch (Exception e) {
	    // Not "visible" to Playwright — still try fill / keyboard
	    try {
		search.evaluate("(el, q) => { el.focus(); el.value = ''; el.value = q; "
			+ "el.dispatchEvent(new Event('input', { bubbles: true })); "
			+ "el.dispatchEvent(new Event('keyup', { bubbles: true })); }", query);
	    } catch (Exception e2) {
		log.warn("{}: could not type into select2 search: {}", desc, e2.getMessage());
		return false;
	    }
	}
	Thread.sleep(600);
	return pickSelect2Result(query);
    }

    private boolean typeViaFocusser(Locator scope, String query, String desc)
	    throws InterruptedException {
	try {
	    Locator focusser = scope.locator(
		    "xpath=.//label[normalize-space()='RFI Provider']/following::input[contains(@class,'select2-focusser')][1]")
		    .first();
	    if (focusser.count() == 0) {
		focusser = scope.locator("input.select2-focusser").first();
	    }
	    if (focusser.count() == 0) {
		return false;
	    }
	    focusser.focus();
	    Thread.sleep(150);
	    page.keyboard().type(query);
	    Thread.sleep(700);
	    if (pickSelect2Result(query)) {
		log.info("{}: selected via focusser keyboard", desc);
		return true;
	    }
	    page.keyboard().press("Enter");
	    Thread.sleep(300);
	    dismissSelect2();
	    // Check if chosen updated
	    Locator chosen = scope.locator(
		    "xpath=.//label[normalize-space()='RFI Provider']/following::span[contains(@class,'select2-chosen')][1]")
		    .first();
	    if (chosen.count() > 0) {
		String after = chosen.innerText().trim();
		if (!after.isBlank() && providerNamesMatch(after, query)) {
		    log.info("{}: Enter selected '{}'", desc, after);
		    return true;
		}
	    }
	} catch (Exception e) {
	    log.warn("{} focusser path failed: {}", desc, e.getMessage());
	}
	return false;
    }

    private boolean openSelect2ViaJquery(String inputSelector) {
	try {
	    Object result = page.evaluate("(sel) => {"
		    + "  const input = document.querySelector(sel);"
		    + "  if (!input) return 'missing';"
		    + "  const $ = window.jQuery || window.$;"
		    + "  if ($ && $.fn && $.fn.select2) {"
		    + "    try { $(input).select2('open'); return 'opened'; }"
		    + "    catch (e) { return 'err:' + e.message; }"
		    + "  }"
		    + "  return 'no-jquery';"
		    + "}", inputSelector);
	    log.info("select2 open via jQuery: {}", result);
	    Thread.sleep(300);
	    return "opened".equals(String.valueOf(result));
	} catch (Exception e) {
	    log.warn("jQuery select2 open failed: {}", e.getMessage());
	    return false;
	}
    }

    private void fillProcedure(String procedure) throws InterruptedException {
	dismissSelect2();
	scrollRfiIntoView();

	String current = "";
	try {
	    Locator scope = page.locator(RFI_FORM).first();
	    Locator c = scope.locator(
		    "xpath=.//label[normalize-space()='Procedure']/following::span[contains(@class,'select2-chosen')][1]")
		    .first();
	    if (c.count() > 0) {
		current = c.innerText().trim();
	    }
	} catch (Exception ignored) {
	}
	if (normalizeCode(current).equals(normalizeCode(procedure))) {
	    PlayTestActionLog.skip("RFI Procedure", current);
	    return;
	}

	PlayTestActionLog.update("RFI Procedure", "'" + current + "' -> '" + procedure + "'");

	// Prefer native <select> — options are charge codes already on the form
	if (selectProcedureFromNative(procedure)) {
	    return;
	}

	Locator scope = page.locator(RFI_FORM).first();
	Locator choice = scope.locator(
		"xpath=.//label[normalize-space()='Procedure']/following::a[contains(@class,'select2-choice')][1]")
		.first();
	if (choice.count() > 0) {
	    try {
		choice.scrollIntoViewIfNeeded();
	    } catch (Exception ignored) {
	    }
	    if (openSelect2AndPick(choice, procedure, "RFI Procedure")) {
		return;
	    }
	}

	String available = listNativeOptionLabels(PROCEDURE_SELECT);
	PlayTestActionLog.skip("RFI Procedure",
		"no matching option for '" + procedure + "'"
			+ (available.isBlank() ? "" : " (available: " + available + ")"));
    }

    private boolean selectProcedureFromNative(String procedure) {
	try {
	    Locator select = page.locator(PROCEDURE_SELECT).first();
	    if (select.count() == 0) {
		return false;
	    }
	    try {
		select.scrollIntoViewIfNeeded();
	    } catch (Exception ignored) {
	    }
	    Locator options = select.locator("option");
	    String want = normalizeCode(procedure);
	    String matchValue = null;
	    String matchLabel = null;
	    for (int i = 0; i < options.count(); i++) {
		String text = options.nth(i).innerText().trim();
		String value = options.nth(i).getAttribute("value");
		if (normalizeCode(text).equals(want) || normalizeCode(value).equals(want)) {
		    matchValue = value != null && !value.isBlank() ? value : text;
		    matchLabel = text.isBlank() ? value : text;
		    break;
		}
	    }
	    if (matchValue == null) {
		return false;
	    }
	    select.selectOption(matchValue);
	    notifyAngularSelect(select);
	    // Sync Select2 chosen text if present
	    try {
		select.evaluate("(el, label) => {"
			+ "  const $ = window.jQuery || window.$;"
			+ "  if ($ && $.fn && $.fn.select2) {"
			+ "    try { $(el).select2('val', el.value).trigger('change'); } catch (e) {}"
			+ "  }"
			+ "  const container = el.previousElementSibling;"
			+ "  if (container && container.classList && container.classList.contains('select2-container')) {"
			+ "    const chosen = container.querySelector('.select2-chosen');"
			+ "    if (chosen) chosen.textContent = label;"
			+ "  }"
			+ "}", matchLabel);
	    } catch (Exception ignored) {
	    }
	    log.info("RFI Procedure set via native select to '{}'", matchLabel);
	    PlayTestActionLog.update("RFI Procedure", "native select -> '" + matchLabel + "'");
	    return true;
	} catch (Exception e) {
	    log.warn("Native RFI Procedure select failed: {}", e.getMessage());
	}
	return false;
    }

    private void fillReasons(List<String> reasons) throws InterruptedException {
	scrollRfiIntoView();
	for (String reason : reasons) {
	    if (reason == null || reason.isBlank()) {
		continue;
	    }
	    String want = reason.trim();
	    if (reasonAlreadySelected(want)) {
		PlayTestActionLog.skip("RFI Reason", want);
		continue;
	    }

	    PlayTestActionLog.update("RFI Reason", "add '" + want + "'");
	    dismissSelect2();

	    if (pickReasonFromNativeSelect(want)) {
		continue;
	    }

	    Locator scope = page.locator(RFI_FORM).first();
	    Locator container = scope.locator(
		    "xpath=.//label[normalize-space()='Reasons']/following::div[contains(@class,'select2-container')][1]")
		    .first();
	    if (container.count() == 0) {
		PlayTestActionLog.skip("RFI Reason", "select2 not on form for '" + want + "'");
		continue;
	    }

	    Locator search = container.locator("ul.select2-choices input.select2-input, input.select2-input")
		    .first();
	    if (search.count() == 0 || !isUsable(search)) {
		container.click(new Locator.ClickOptions().setForce(true));
		Thread.sleep(200);
		search = container.locator("input.select2-input").first();
	    }
	    if (search.count() == 0) {
		Locator fallback = waitForSelect2Search(6);
		if (fallback != null) {
		    search = fallback;
		}
	    }
	    if (search.count() == 0) {
		PlayTestActionLog.skip("RFI Reason " + want, "search input not found");
		continue;
	    }
	    try {
		search.click(new Locator.ClickOptions().setForce(true));
		search.fill("");
		search.fill(want);
	    } catch (Exception e) {
		search.evaluate("(el, q) => { el.focus(); el.value = q; "
			+ "el.dispatchEvent(new Event('input', { bubbles: true })); }", want);
	    }
	    Thread.sleep(400);
	    if (!pickSelect2Result(want)) {
		PlayTestActionLog.skip("RFI Reason", "no match for '" + want + "'");
	    }
	    dismissSelect2();
	    Thread.sleep(200);
	}
    }

    private boolean reasonAlreadySelected(String want) {
	try {
	    Locator scope = page.locator(RFI_FORM).first();
	    Locator chips = scope.locator(".select2-search-choice, .select2-selection__choice");
	    String lower = want.toLowerCase(Locale.ROOT);
	    for (int i = 0; i < chips.count(); i++) {
		String t = chips.nth(i).innerText().replace("×", "").trim();
		if (t.equalsIgnoreCase(want) || t.toLowerCase(Locale.ROOT).contains(lower)) {
		    return true;
		}
	    }
	    Locator select = page.locator(REASONS_SELECT).first();
	    if (select.count() > 0) {
		Locator selected = select.locator("option:checked");
		for (int i = 0; i < selected.count(); i++) {
		    String t = selected.nth(i).innerText().trim();
		    if (t.equalsIgnoreCase(want) || t.toLowerCase(Locale.ROOT).contains(lower)) {
			return true;
		    }
		}
	    }
	} catch (Exception ignored) {
	}
	return false;
    }

    private boolean pickReasonFromNativeSelect(String reason) {
	try {
	    Locator select = page.locator(REASONS_SELECT).first();
	    if (select.count() == 0) {
		return false;
	    }
	    Locator options = select.locator("option");
	    String want = reason.toLowerCase(Locale.ROOT);
	    String value = null;
	    String label = null;
	    for (int i = 0; i < options.count(); i++) {
		String text = options.nth(i).innerText().trim();
		String v = options.nth(i).getAttribute("value");
		String lower = text.toLowerCase(Locale.ROOT);
		if (lower.equals(want) || lower.contains(want)) {
		    value = v;
		    label = text;
		    if (lower.equals(want)) {
			break;
		    }
		}
	    }
	    if (label == null) {
		return false;
	    }

	    final String selectedValue = value != null ? value : "";
	    final String selectedLabel = label;
	    Object result = select.evaluate("(el, payload) => {"
		    + "  const v = payload.value;"
		    + "  const label = payload.label;"
		    + "  const opts = Array.from(el.options);"
		    + "  const opt = opts.find(o => String(o.value) === String(v))"
		    + "    || opts.find(o => (o.textContent || '').trim() === label);"
		    + "  if (!opt) return 'no-opt';"
		    + "  opt.selected = true;"
		    + "  const selected = opts.filter(o => o.selected).map(o => o.value);"
		    + "  const $ = window.jQuery || window.$;"
		    + "  if ($ && $.fn && $.fn.select2) {"
		    + "    try { $(el).select2('val', selected).trigger('change'); } catch (e) {}"
		    + "  }"
		    + "  el.dispatchEvent(new Event('change', { bubbles: true }));"
		    + "  el.dispatchEvent(new Event('input', { bubbles: true }));"
		    + "  if (window.angular) {"
		    + "    const ae = angular.element(el);"
		    + "    let s = ae.scope && ae.scope();"
		    + "    for (let i = 0; i < 8 && s; i++) {"
		    + "      if (s.codingRFI) {"
		    + "        try {"
		    + "          s.$apply(() => {"
		    + "            const ids = Array.isArray(s.codingRFI.reasonIds) ? s.codingRFI.reasonIds.slice() : [];"
		    + "            const idNum = isNaN(Number(v)) ? v : Number(v);"
		    + "            if (!ids.map(String).includes(String(v)) && !ids.map(String).includes(String(idNum))) {"
		    + "              ids.push(idNum);"
		    + "            }"
		    + "            s.codingRFI.reasonIds = ids;"
		    + "          });"
		    + "        } catch (e) {}"
		    + "        return 'angular';"
		    + "      }"
		    + "      s = s.$parent;"
		    + "    }"
		    + "    try { ae.triggerHandler('change'); } catch (e) {}"
		    + "  }"
		    + "  return 'dom';"
		    + "}", Map.of("value", selectedValue, "label", selectedLabel));

	    log.info("RFI Reason native select '{}' result={}", selectedLabel, result);
	    PlayTestActionLog.update("RFI Reason", "native select -> '" + selectedLabel + "'");
	    try {
		Thread.sleep(200);
	    } catch (InterruptedException ie) {
		Thread.currentThread().interrupt();
	    }
	    return true;
	} catch (Exception e) {
	    log.warn("Native RFI reason select failed: {}", e.getMessage());
	}
	return false;
    }

    private void fillComment(String comment) {
	Locator area = page.locator(COMMENT).first();
	if (area.count() == 0) {
	    PlayTestActionLog.skip("RFI Comment", "textarea not on form");
	    return;
	}
	try {
	    area.scrollIntoViewIfNeeded();
	} catch (Exception ignored) {
	}
	String current = "";
	try {
	    current = area.inputValue();
	} catch (Exception ignored) {
	}
	if (comment.equals(current)) {
	    PlayTestActionLog.skip("RFI Comment", current);
	    return;
	}
	PlayTestActionLog.update("RFI Comment", "set from JSON");
	area.click(new Locator.ClickOptions().setForce(true));
	area.fill("");
	area.fill(comment);
	try {
	    area.evaluate("el => {"
		    + " el.dispatchEvent(new Event('input', { bubbles: true })); "
		    + " el.dispatchEvent(new Event('change', { bubbles: true })); "
		    + " if (window.angular) {"
		    + "   const s = angular.element(el).scope();"
		    + "   if (s) { try { s.$apply(); } catch (e) {} }"
		    + " }"
		    + "}");
	} catch (Exception ignored) {
	}
    }

    private boolean typeAndPickSelect2(String query, String desc) throws InterruptedException {
	Locator search = waitForSelect2Search(12);
	if (search == null) {
	    log.warn("{}: select2 search not visible", desc);
	    return false;
	}
	try {
	    search.fill("");
	    search.fill(query);
	} catch (Exception e) {
	    search.evaluate("(el, q) => { el.focus(); el.value = q; "
		    + "el.dispatchEvent(new Event('input', { bubbles: true })); }", query);
	}
	Thread.sleep(600);
	return pickSelect2Result(query);
    }

    private Locator waitForSelect2Search(int attempts) throws InterruptedException {
	String[] selectors = {
		".select2-drop-active .select2-search input.select2-input",
		".select2-drop:not(.select2-display-none) .select2-search input.select2-input",
		"#select2-drop .select2-search input.select2-input",
		".select2-drop-active input.select2-input",
		"input.select2-input.select2-focused",
		"input.select2-input:focus"
	};
	for (int a = 0; a < attempts; a++) {
	    for (String sel : selectors) {
		try {
		    Locator loc = page.locator(sel).first();
		    if (loc.count() > 0 && isUsable(loc)) {
			return loc;
		    }
		    // Present in open drop even if Playwright says not visible
		    if (loc.count() > 0) {
			Locator drop = page.locator(
				".select2-drop-active, .select2-drop:not(.select2-display-none), #select2-drop")
				.first();
			if (drop.count() > 0) {
			    return loc;
			}
		    }
		} catch (Exception ignored) {
		}
	    }
	    Thread.sleep(150);
	}
	return null;
    }

    private boolean isUsable(Locator loc) {
	try {
	    return loc.count() > 0 && (loc.isVisible() || loc.isEnabled());
	} catch (Exception e) {
	    return loc.count() > 0;
	}
    }

    private boolean pickSelect2Result(String query) throws InterruptedException {
	Locator results = page.locator(
		".select2-drop-active .select2-results li.select2-result-selectable, "
			+ ".select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable, "
			+ "#select2-drop .select2-results li.select2-result-selectable");
	for (int i = 0; i < 10 && results.count() == 0; i++) {
	    if (page.locator(
		    ".select2-drop-active li.select2-no-results, "
			    + ".select2-drop:not(.select2-display-none) li.select2-no-results, "
			    + "#select2-drop li.select2-no-results")
		    .count() > 0) {
		dismissSelect2();
		return false;
	    }
	    Thread.sleep(200);
	}
	if (results.count() == 0) {
	    return false;
	}
	String want = query.trim().toLowerCase(Locale.ROOT);
	String wantCode = normalizeCode(query);
	Locator match = null;
	for (int i = 0; i < results.count(); i++) {
	    String text = results.nth(i).innerText().trim();
	    String lower = text.toLowerCase(Locale.ROOT);
	    String code = normalizeCode(text);
	    if (lower.equals(want) || code.equals(wantCode) || providerNamesMatch(text, query)
		    || lower.startsWith(want) || lower.contains(want) || code.startsWith(wantCode)) {
		match = results.nth(i);
		if (lower.equals(want) || code.equals(wantCode) || providerNamesMatch(text, query)
			|| lower.startsWith(want)) {
		    break;
		}
	    }
	}
	if (match == null) {
	    return false;
	}
	match.click(new Locator.ClickOptions().setForce(true));
	Thread.sleep(250);
	dismissSelect2();
	return true;
    }

    private void notifyAngularSelect(Locator select) {
	try {
	    select.evaluate("el => {"
		    + " el.dispatchEvent(new Event('change', { bubbles: true }));"
		    + " el.dispatchEvent(new Event('input', { bubbles: true }));"
		    + " if (window.angular) {"
		    + "   const ae = angular.element(el);"
		    + "   try { ae.triggerHandler('change'); } catch (e) {}"
		    + "   const s = ae.scope && ae.scope();"
		    + "   if (s) { try { s.$apply(); } catch (e) {} }"
		    + " }"
		    + "}");
	} catch (Exception ignored) {
	}
    }

    private String listNativeOptionLabels(String selectSelector) {
	try {
	    Locator select = page.locator(selectSelector).first();
	    if (select.count() == 0) {
		return "";
	    }
	    Locator options = select.locator("option");
	    List<String> labels = new ArrayList<>();
	    for (int i = 0; i < options.count() && labels.size() < 12; i++) {
		String text = options.nth(i).innerText().trim();
		String value = options.nth(i).getAttribute("value");
		if ((text == null || text.isBlank()) && (value == null || value.isBlank())) {
		    continue;
		}
		labels.add(text != null && !text.isBlank() ? text : value);
	    }
	    return String.join(", ", labels);
	} catch (Exception e) {
	    return "";
	}
    }

    private void dismissSelect2() {
	try {
	    if (page.locator("#select2-drop-mask").count() > 0
		    || page.locator(".select2-drop-active, .select2-drop:not(.select2-display-none)")
			    .count() > 0) {
		page.keyboard().press("Escape");
		Thread.sleep(150);
		page.keyboard().press("Escape");
		Thread.sleep(100);
	    }
	} catch (Exception ignored) {
	}
    }

    /** Loose match: "Edwin Doig" vs "Doig, Edwin" / "Doig MD, Edwin". */
    private static boolean providerNamesMatch(String a, String b) {
	if (a == null || b == null) {
	    return false;
	}
	String na = normalizeProvider(a);
	String nb = normalizeProvider(b);
	if (na.equals(nb) || na.contains(nb) || nb.contains(na)) {
	    return true;
	}
	// Last, First vs First Last
	String[] pa = na.split("\\s+");
	String[] pb = nb.split("\\s+");
	if (pa.length >= 2 && pb.length >= 2) {
	    String aFirst = pa[0];
	    String aLast = pa[pa.length - 1];
	    String bFirst = pb[0];
	    String bLast = pb[pb.length - 1];
	    if ((aFirst.equals(bLast) && aLast.equals(bFirst))
		    || (aFirst.equals(bFirst) && aLast.equals(bLast))) {
		return true;
	    }
	}
	return false;
    }

    private static String normalizeProvider(String raw) {
	return raw.trim().toLowerCase(Locale.ROOT)
		.replace(",", " ")
		.replaceAll("\\b(md|do|np|pa|phd|rn)\\b", " ")
		.replaceAll("[^a-z0-9\\s]", " ")
		.replaceAll("\\s+", " ")
		.trim();
    }

    private static String normalizeCode(String raw) {
	if (raw == null) {
	    return "";
	}
	return raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String str(Map<String, Object> m, String key) {
	Object v = m.get(key);
	if (v == null) {
	    return null;
	}
	String s = String.valueOf(v).trim();
	return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }
}
