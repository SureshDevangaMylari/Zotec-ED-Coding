package com.wl.util;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import com.wl.zotecAgent.FlowText;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Page.GetByRoleOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class PlaywrightService {

    Page page;
    public int timeout = 5 * 60;
    private static final Logger logger = LogManager.getLogger(PlaywrightService.class);
    private static final int DEFAULT_WAIT = 30000; // 30 seconds

    public PlaywrightService(Page page) {
	this.page = page;
    }

    public Locator waitForElement(Locator locator, String description) {
	try {
	    int effectiveTimeout = timeout * 1000;
	    logger.error("Locating ... element [{}] - {}", description + "  time " + effectiveTimeout);

	    locator.first().waitFor(
		    new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(effectiveTimeout));
	    logger.error("Located element [{}] - {}", description);
	    return locator.first();
	} catch (Exception e) {
	    logger.error("Failed to locate [{}] after {}s - {}", description);
	    throw e;
	}
    }

    public void waitForLoadingToDisappear(Locator loadingLocator, String description) {

	while (true) {
	    try {
		int effectiveTimeout = timeout * 1000;

		if (loadingLocator.isVisible()) {
		    logger.error("Waiting for [{}] to disappear... (timeout: {} ms)", description, effectiveTimeout);
		    loadingLocator.innerText();
		    // Only wait if the locator exists

		    logger.error("[{}] has visibile. Continuing...", loadingLocator);

		} else {
		    logger.error("[{}] is not present. Continuing without waiting...", description);
		    break;
		}
		Thread.sleep(1000);
	    } catch (Exception e) {
		e.printStackTrace();
		logger.error(" error ", e.getMessage());
		break;

	    }

	}
    }

    public Locator waitForElement(String selector, String description, int timeout) {
	logger.error("Locating element [{}] - {}", description, selector);
	try {
	    int effectiveTimeout = (timeout == 0) ? DEFAULT_WAIT : timeout * 1000;
	    Locator locator = page.locator(selector).first();
	    locator.waitFor(
		    new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(effectiveTimeout));
	    logger.error("Located element [{}] - {}", description, selector);
	    return locator;
	} catch (Exception e) {
	    logger.error("Failed to locate [{}] after {}s - {}", description, timeout, selector);
	    throw e;
	}
    }

    public Locator waitfor(Locator loc, String description, int timeout) {
	try {
	    logger.error("waiting for  element [{}] - {}", description);
	    int effectiveTimeout = (timeout == 0) ? DEFAULT_WAIT : timeout * 1000;
	    Locator locator = loc.first();
	    locator.waitFor(
		    new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(effectiveTimeout));
	    logger.error("Located element [{}] - {}", description);
	    return locator.first();
	} catch (Exception e) {
	    logger.error("Failed to locate [{}] after {}s - {}", description, timeout);
	    throw e;
	}
    }

    /** Returns a locator for the given selector. Use instead of page.locator(). */
    public Locator locator(String selector) {
	return page.locator(selector);
    }

    /** Returns a locator for role within a container (e.g. TEXTBOX within #select2-drop). */
    public Locator getByRoleWithin(String containerSelector, AriaRole role) {
	return page.locator(containerSelector).getByRole(role);
    }

    /** Returns a locator for element with text within a container (e.g. #select2-drop). */
    public Locator getByTextWithin(String containerSelector, String text) {
	return page.locator(containerSelector).getByText(text);
    }

    /** Returns a locator for divs within container that match the text pattern (exact match). */
    public Locator locatorFilteredByText(String containerSelector, Pattern textPattern) {
	return page.locator(containerSelector + " div").filter(new Locator.FilterOptions().setHasText(textPattern));
    }

    /** Scrolls element into view and clicks with force. */
    public void scrollAndClick(Locator locator, String description) {
	locator.first().scrollIntoViewIfNeeded();
	locator.first().click(new Locator.ClickOptions().setForce(true));
	logger.error("Clicked [{}] - {}", description, locator);
    }

    /** Waits for locator to be visible (timeout in ms), then clicks and fills. */
    public void waitForVisibleThenFill(Locator locator, String text, String description, int timeoutMs) {
	locator.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(timeoutMs));
	locator.first().click();
	locator.first().fill(text);
	logger.error("Filled '{}' into [{}]", text, description);
    }

    /** Sets value on a readonly input (e.g. datepicker). Removes readonly, sets value, dispatches input/change. */
    public void setReadonlyInputValue(Locator locator, String value, String description) {
	locator.first().evaluate(
		"(el, val) => { el.removeAttribute('readonly'); el.value = val; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); }",
		value);
	logger.error("Set '{}' on [{}]", value, description);
    }

    /** If locator matches any element, clicks the first and returns true. Otherwise returns false. */
    public boolean clickIfPresent(Locator locator, String description) {
	if (locator.count() > 0) {
	    click(locator.first(), description);
	    return true;
	}
	return false;
    }

    // --- BASIC ACTIONS ---
    public void click(Locator locate, String description) {
	while (true) {
	    try {
		logger.error("Clicking [{}] - {} ", description, locate);
		Locator element = waitForElement(locate, description).first();
		element.click();
		logger.error("Clicked [{}] - {} ", description, locate);
		break;

	    } catch (Exception e) {
		// TODO: handle exception
		e.printStackTrace();
		try {
		    Thread.sleep(1000);
	    } catch (InterruptedException e1) {
		    // TODO Auto-generated catch block
		    e1.printStackTrace();
		}
	    }
	}

    }

    /**
     * Waits for the locator to be visible (with given timeout in seconds), then clicks.
     *
     * @param locator     element to click
     * @param description description for logging
     * @param timeoutSec  timeout in seconds (0 = use default 30s)
     */
    public void click(Locator locator, String description, int timeoutSec) {
		
	int effectiveTimeout = (timeoutSec == 0) ? DEFAULT_WAIT : timeoutSec * 1000;
	logger.error("Clicking [{}] (timeout: {} ms)", description, effectiveTimeout);
	locator.first().waitFor(
		new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(effectiveTimeout));
	locator.first().click();
	logger.error("Clicked [{}]", description);
    }

    public void type(String selector, String text, String description, int timeout) {
	Locator element = waitForElement(selector, description, timeout).first();
	element.fill(text);
	logger.error("Typed '{}' into [{}] - {} ", text, description, selector);
    }

    public void fill(Locator locater, String text, String description) throws Exception {

	Locator element = waitForElement(locater, description).first();
	waitfor(element, description, 30);
	element.fill(text);
	logger.error("Typed '{}' into [{}] - {} ", text, description);
    }

    public void clear(String selector, String description, int timeout) {
	Locator element = waitForElement(selector, description, timeout).first();
	element.fill("");
	logger.error("Cleared [{}] - {}", description, selector);
    }

    public void clickAndType(String selector, String text, String description, int timeout) {
	Locator element = waitForElement(selector, description, timeout);
	element.click();
	element.fill(text);
	logger.error("Clicked and typed '{}' into [{}] - {}", text, description, selector);
    }

    /**
     * Clicks an element found by role (like 'button', 'link', 'textbox'), logs the
     * action.
     */
    public void clickByRole(AriaRole role, String name) {
	System.out.println("🖱️ Clicking element by role: [" + role + "] with name: [" + name + "]");
	page.getByRole(role, new Page.GetByRoleOptions().setName(name)).click();
    }

    /**
     * Types text into a field found by role, logs the action.
     */
    public void typeByRole(AriaRole role, String name, String text) {
	System.out.println("⌨️ Typing into [" + role + "] with name: [" + name + "] => " + text);
	page.getByRole(role, new Page.GetByRoleOptions().setName(name)).fill(text);
    }

    /**
     * Waits for an element by role to be visible (explicit wait).
     */
    public void waitForVisibleByrole(AriaRole role, String name, int timeoutMillis) {
	System.out.println("⏳ Waiting for [" + role + "] with name [" + name + "] to be visible...");
	Locator locator = page.getByRole(role, new Page.GetByRoleOptions().setName(name));
	locator.waitFor(new Locator.WaitForOptions().setTimeout(timeoutMillis));
	System.out.println("✅ Element is visible.");
    }

    // --- WAIT UTILS ---
    public void waitForVisible(String selector, String description, int timeout) {
	waitForElement(selector, description, timeout);
    }

    /**
     * Waits until the element disappears (hidden or detached), or throws after max time.
     *
     * @param locator    element to wait for disappearing
     * @param maxWaitSec max time to wait in seconds
     */
    public void waitUntilElementDisappear(Locator locator, int maxWaitSec) {
	int maxWaitMs = maxWaitSec * 1000;
	locator.first().waitFor(
		new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(maxWaitMs));
	logger.info("Element disappeared within {} seconds", maxWaitSec);
    }

    /**
     * Waits until the element with given selector disappears, or throws after max time.
     *
     * @param selector   CSS selector for the element
     * @param maxWaitSec max time to wait in seconds
     */
    public void waitUntilElementDisappear(String selector, int maxWaitSec) {
	waitUntilElementDisappear(page.locator(selector), maxWaitSec);
    }

    public void waitWhileLoad(int timeout) {
	try {
	    Thread.sleep(timeout * 1000L);
	    logger.error("Waited for {} seconds", timeout);
	} catch (InterruptedException e) {
	    logger.error("Interrupted during wait");
	    Thread.currentThread().interrupt();
	}
    }

    // --- GET TEXT ---
    public String getText(Locator locator, String description) {
	Locator element = waitForElement(locator.first(), description);
	String text = element.innerText();
	logger.error("Got text '{}' from [{}] - {}", text, description);
	return text;
    }

    public String getInputValue(Locator locator, String description) {
	Locator element = waitForElement(locator.first(), description);
	String text = element.inputValue();
	logger.error("Got text '{}' from [{}] - {}", text, description);
	return text;
    }

    public String getValue(String selector, String description, int timeout) {
	Locator element = waitForElement(selector, description, timeout);
	String value = element.inputValue();
	logger.error("Got input value '{}' from [{}] - {}", value, description, selector);
	return value;
    }

    // --- ADVANCED ACTIONS ---
    public void dblClick(Locator locator, String description) {
	Locator element = waitForElement(locator, description);
	element.dblclick();
	logger.error("Double-clicked [{}] - {}", description);
    }

    public boolean isDisplayed(Locator locate, String description) {
	try {
	    Locator element = waitfor(locate, description, 2);
	    boolean visible = element.isVisible();
	    logger.error("[{}] displayed: {}", description, visible);
	    return visible;
	} catch (Exception e) {
	    logger.warn("[{}] not displayed", description);
	    return false;
	}
    }

    public void pressEnter(String selector, String description, int timeout) {
	Locator element = waitForElement(selector, description, timeout);
	element.press("Enter");
	logger.error("Pressed ENTER on [{}] - {}", description, selector);
    }

    public void pressEnter(Locator element, String description, int timeout) {
	element.press("Enter");
	logger.error("Pressed ENTER on [{}] - {}", description);
    }

    // --- TABS & WINDOWS ---
    public void openNewTab(BrowserContext context) {
	context.newPage();
	logger.error("Opened new tab");
    }

    public void switchToTab(BrowserContext context, int index) {
	List<Page> pages = context.pages();
	if (index < pages.size()) {
	    pages.get(index).bringToFront();
	    logger.error("Switched to tab index {}", index);
	} else {
	    throw new RuntimeException("Tab index " + index + " not found");
	}
    }

    // --- IFRAMES ---
    public FrameLocator switchToIframe(String selector, String description, int timeout) {
	waitForElement(selector, description, timeout);
	logger.error("Switched to iframe [{}] - {}", description, selector);
	return page.frameLocator(selector);
    }

    // --- WEB ELEMENTS ---
    public List<Locator> getElements(String selector, String description) {
	try {
	    waitForElement(selector, description, 5);
	    List<Locator> elements = page.locator(selector).all();
	    logger.error("Found {} elements for [{}] - {}", elements.size(), description, selector);
	    return elements;
	} catch (Exception e) {
	    e.printStackTrace();
	    logger.error("No elements found [{}] - {}", description, selector);

	    return new ArrayList<Locator>();
	}
    }

    public Locator getElement(String selector, String description) {
	return waitForElement(selector, description, timeout);
    }

    // --- CHECKBOX ---
    public void selectCheckboxByValue(Locator locator, String value, String description) {
	logger.error(" locator value " + value);
	try {
	    Locator loc = waitForElement(locator, description);

	    loc.selectOption(value);
	} catch (Exception e) {
	    throw new RuntimeException("Checkbox with value '" + value + "' not found in " + description);

	}

    }

    // --- DROPDOWN ---
    public void selectByVisibleText(String selector, String text, String description, int timeout) {
	Locator element = waitForElement(selector, description, timeout);
	element.selectOption(new SelectOption().setLabel(text));
	logger.error("Selected '{}' from [{}] - {}", text, description, selector);
    }

    public void selectByIndex(String selector, int index, String description, int timeout) {
	Locator element = waitForElement(selector, description, timeout);
	element.selectOption(new SelectOption().setIndex(index));
	logger.error("Selected index {} from [{}] - {}", index, description, selector);
    }

    // --- TABLE ---
    public Locator getTableRow(String tableSelector, int rowIndex, String description, int timeout) {
	Locator table = waitForElement(tableSelector, description, timeout);
	Locator row = table.locator("tr").nth(rowIndex);
	logger.error("Got row {} from table [{}]", rowIndex, description);
	return row;
    }

    public Locator getTable(String selector, String description, int timeout) {
	return waitForElement(selector, description, timeout);
    }

}
