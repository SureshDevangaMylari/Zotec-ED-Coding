package com.wl.zotecAgent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Reads the Coding Workfile {@code div.well.well-sm} summary table (Client, DOS, POS,
 * Gender, Location, Age, Carrier Type, Carrier) plus Charge ID for upload {@code metadata}.
 */
public final class WorkfileSummaryScraper {

    private static final Logger log = LoggerFactory.getLogger(WorkfileSummaryScraper.class);

    private static final String WELL = "div.well.well-sm";
    private static final Pattern LEADING_COUNT = Pattern.compile("^\\d+\\s*");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    /** Visible text like {@code Charge ID: 6267729 • }. */
    private static final Pattern CHARGE_ID_PATTERN =
	    Pattern.compile("Charge\\s*ID\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final double CHARGE_ID_WAIT_MS = 60_000;

    private WorkfileSummaryScraper() {
    }

    /**
     * @param selectedClientLocation label from Select client(s) for the active checkbox
     *                               (stored as {@code client_location})
     */
    public static Map<String, Object> build(Page page, String selectedClientLocation) {
	Map<String, Object> meta = new LinkedHashMap<>();
	putIfPresent(meta, "client_location", cleanClientLocation(selectedClientLocation));

	if (page == null) {
	    log.warn("Workfile summary scrape skipped — page is null");
	    return meta;
	}

	try {
	    Locator well = page.locator(WELL).first();
	    if (well.count() > 0 && well.isVisible()) {
		putIfPresent(meta, "client", cellText(well, "Client"));
		putIfPresent(meta, "Batch Text", cellText(well, "Batch Text"));
		putIfPresent(meta, "dos", cellText(well, "DOS"));
		putIfPresent(meta, "pos", cellText(well, "POS"));
		putIfPresent(meta, "gender", cellText(well, "Gender"));
		putIfPresent(meta, "location", cellText(well, "Location"));
		putIfPresent(meta, "age", cellText(well, "Age"));
		putIfPresent(meta, "carrier_type", cellText(well, "Carrier Type"));
		putIfPresent(meta, "carrier", cellText(well, "Carrier"));
	    } else {
		log.warn("Workfile summary well not found — will still wait for Charge ID");
	    }

	    // Charge ID is on the coding form (small "Charge ID: N"), not the summary well.
	    // Wait until Angular renders it so upload form_data is not missing/null.
	    putIfPresent(meta, "chargeID", waitAndReadChargeId(page));

	    log.info("Workfile upload metadata: {}", meta);
	} catch (Exception e) {
	    log.warn("Failed to scrape workfile summary well: {}", e.getMessage());
	}
	return meta;
    }

    /**
     * Waits until a Charge ID label is visible (text or image charts), then returns the number.
     * Does not put a null placeholder — returns null only if it never appears.
     */
    static String waitAndReadChargeId(Page page) {
	if (page == null) {
	    return null;
	}
	try {
	    Locator chargeLabel = page.locator("small")
		    .filter(new Locator.FilterOptions().setHasText("Charge ID:"))
		    .first();
	    log.info("Waiting up to {}s for Charge ID to load on workfile",
		    (long) (CHARGE_ID_WAIT_MS / 1000));
	    chargeLabel.waitFor(new Locator.WaitForOptions()
		    .setState(WaitForSelectorState.VISIBLE)
		    .setTimeout(CHARGE_ID_WAIT_MS));
	    String text = chargeLabel.innerText();
	    if (text == null || text.isBlank()) {
		text = chargeLabel.textContent();
	    }
	    String id = parseChargeId(text);
	    if (id != null) {
		log.info("Captured chargeID={}", id);
		return id;
	    }
	    log.warn("Charge ID element visible but number not parsed from '{}'", text);
	} catch (Exception e) {
	    log.warn("Charge ID not available after wait: {}", e.getMessage());
	}
	return null;
    }

    static String parseChargeId(String raw) {
	if (raw == null || raw.isBlank()) {
	    return null;
	}
	Matcher m = CHARGE_ID_PATTERN.matcher(raw.replace('\u00a0', ' ').trim());
	if (m.find()) {
	    return m.group(1);
	}
	return null;
    }

    /** Visible label text for a Select client(s) checkbox (no checkbox id prefix). */
    public static String readSelectedClientDisplayName(Locator checkbox) {
	if (checkbox == null) {
	    return null;
	}
	try {
	    Locator label = checkbox.locator("xpath=ancestor::label[1]");
	    String raw;
	    if (label.count() > 0) {
		raw = label.first().innerText();
	    } else {
		raw = checkbox.locator("xpath=..").innerText();
	    }
	    return cleanClientLocation(raw);
	} catch (Exception e) {
	    log.warn("Could not read Select client(s) display name: {}", e.getMessage());
	    return null;
	}
    }

    static String cleanClientLocation(String raw) {
	if (raw == null || raw.isBlank()) {
	    return null;
	}
	String s = MULTI_SPACE.matcher(raw.trim()).replaceAll(" ");
	s = LEADING_COUNT.matcher(s).replaceFirst("");
	s = s.replace('\u00a0', ' ').trim();
	return s.isEmpty() ? null : s;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
	if (value != null && !value.isBlank()) {
	    map.put(key, value.trim());
	}
    }

    /**
     * Finds {@code <th>Header</th><td>...</td>} text inside the well (first match).
     */
    private static String cellText(Locator well, String header) {
	try {
	    Locator ths = well.locator("th");
	    for (int i = 0; i < ths.count(); i++) {
		String th = ths.nth(i).innerText();
		if (th == null) {
		    continue;
		}
		if (!header.equalsIgnoreCase(th.trim())) {
		    continue;
		}
		Locator td = ths.nth(i).locator("xpath=following-sibling::td[1]");
		if (td.count() == 0) {
		    return null;
		}
		String text = td.first().innerText();
		if (text == null) {
		    return null;
		}
		text = MULTI_SPACE.matcher(text.replace('\u00a0', ' ').trim()).replaceAll(" ");
		return text.isEmpty() ? null : text;
	    }
	} catch (Exception e) {
	    log.debug("cellText('{}') failed: {}", header, e.getMessage());
	}
	return null;
    }
}
