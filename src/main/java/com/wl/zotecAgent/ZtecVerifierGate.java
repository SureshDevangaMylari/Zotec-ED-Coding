package com.wl.zotecAgent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * ZTEC logger verifier overlay: {@code <button class="wl_zt_verifier_action">Yes, I did</button>}
 * sits over Submit / Skip / RFI / Move to Issue until dismissed.
 */
public final class ZtecVerifierGate {

    private static final Logger log = LogManager.getLogger(ZtecVerifierGate.class);

    /** Prefer the ZTEC class; also match by visible label. */
    private static final String CLASS_SELECTOR = "button.wl_zt_verifier_action";

    private ZtecVerifierGate() {
    }

    /**
     * If "Yes, I did" is visible, click it so Submit / Skip / RFI / Move to Issue can be used.
     *
     * @return {@code true} if the button was found and clicked
     */
    public static boolean dismissYesIDidIfPresent(Page page) {
	if (page == null) {
	    return false;
	}
	try {
	    Locator btn = page.locator(CLASS_SELECTOR).first();
	    if (btn.count() == 0 || !btn.isVisible()) {
		btn = page.getByRole(AriaRole.BUTTON,
			new Page.GetByRoleOptions().setName("Yes, I did")).first();
	    }
	    if (btn.count() == 0 || !btn.isVisible()) {
		return false;
	    }
	    log.info("ZTEC 'Yes, I did' visible — clicking so Submit/Skip/RFI/Move to Issue are usable");
	    btn.scrollIntoViewIfNeeded();
	    try {
		btn.click(new Locator.ClickOptions().setTimeout(5_000));
	    } catch (Exception e) {
		log.warn("Normal click on 'Yes, I did' failed ({}) — force click", e.getMessage());
		btn.click(new Locator.ClickOptions().setForce(true).setTimeout(5_000));
	    }
	    try {
		Thread.sleep(500);
	    } catch (InterruptedException ie) {
		Thread.currentThread().interrupt();
	    }
	    return true;
	} catch (Exception e) {
	    log.warn("Could not dismiss ZTEC 'Yes, I did': {}", e.getMessage());
	    return false;
	}
    }
}
