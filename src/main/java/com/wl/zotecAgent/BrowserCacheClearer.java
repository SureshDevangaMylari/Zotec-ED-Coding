package com.wl.zotecAgent;

import java.net.URI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;

/**
 * Clears browser data equivalent to Chrome "Clear browsing data" with time range
 * <b>All time</b> for:
 * <ul>
 * <li>Cookies and other site data</li>
 * <li>Cached images and files</li>
 * </ul>
 * Scoped to the bot's Playwright Chromium instance (not the user's everyday Chrome profile).
 */
public final class BrowserCacheClearer {

    private static final Logger log = LogManager.getLogger(BrowserCacheClearer.class);

    /** {@code since: 0} = All time (same as Chrome Clear browsing data). */
    private static final double SINCE_ALL_TIME = 0;

    private BrowserCacheClearer() {
    }

    /**
     * Clears cookies + other site data and cached images/files for <b>All time</b>.
     */
    public static void clearAll(BrowserContext context, Page page, String when) {
	if (context == null) {
	    log.info("Browser cache clear skipped at {} — no context", when);
	    return;
	}

	// Playwright-level: all cookies in this context (all time for this profile)
	try {
	    context.clearCookies();
	    log.info("Cleared all context cookies (All time) at {}", when);
	} catch (Exception e) {
	    log.warn("clearCookies failed at {}: {}", when, e.getMessage());
	}

	if (page == null) {
	    return;
	}
	try {
	    if (page.isClosed()) {
		return;
	    }
	} catch (Exception e) {
	    return;
	}

	CDPSession cdp = null;
	try {
	    cdp = context.newCDPSession(page);

	    // Chrome UI: "Cookies and other site data" — All time
	    clearCookiesAndSiteDataAllTime(cdp, when);

	    // Chrome UI: "Cached images and files" — All time
	    clearCachedImagesAndFilesAllTime(cdp, when);

	    // Also wipe current origin storage if a real site is open
	    clearOriginStorageViaCdp(cdp, page, when);
	} catch (Exception e) {
	    log.warn("CDP All-time cache/cookie clear failed at {}: {}", when, e.getMessage());
	} finally {
	    if (cdp != null) {
		try {
		    cdp.detach();
		} catch (Exception ignored) {
		}
	    }
	}

	clearPageStorageJs(page, when);
    }

    /**
     * Matches Chrome checkbox "Cookies and other site data" with Time range = All time.
     */
    private static void clearCookiesAndSiteDataAllTime(CDPSession cdp, String when) {
	try {
	    JsonObject dataTypes = new JsonObject();
	    dataTypes.addProperty("cookies", true);
	    dataTypes.addProperty("localStorage", true);
	    dataTypes.addProperty("indexedDB", true);
	    dataTypes.addProperty("cacheStorage", true);
	    dataTypes.addProperty("serviceWorkers", true);
	    dataTypes.addProperty("fileSystems", true);
	    dataTypes.addProperty("appcache", true);
	    dataTypes.addProperty("webSQL", true);
	    // Do not clear passwords / formData / history — not requested

	    JsonObject originTypes = new JsonObject();
	    originTypes.addProperty("unprotectedWeb", true);
	    originTypes.addProperty("protectedWeb", true);

	    JsonObject params = new JsonObject();
	    params.addProperty("since", SINCE_ALL_TIME);
	    params.add("dataTypes", dataTypes);
	    params.add("originTypes", originTypes);

	    cdp.send("BrowsingData.remove", params);
	    log.info("BrowsingData.remove (cookies + site data, All time) at {}", when);
	} catch (Exception e) {
	    log.warn("BrowsingData.remove (site data) failed at {}: {} — falling back", when, e.getMessage());
	    // Fallbacks if BrowsingData domain is unavailable
	    try {
		cdp.send("Network.clearBrowserCookies");
	    } catch (Exception ignored) {
	    }
	    try {
		JsonObject since = new JsonObject();
		since.addProperty("since", SINCE_ALL_TIME);
		cdp.send("BrowsingData.removeCookies", since);
	    } catch (Exception ignored) {
	    }
	    try {
		JsonObject since = new JsonObject();
		since.addProperty("since", SINCE_ALL_TIME);
		cdp.send("BrowsingData.removeLocalStorage", since);
	    } catch (Exception ignored) {
	    }
	    try {
		JsonObject since = new JsonObject();
		since.addProperty("since", SINCE_ALL_TIME);
		cdp.send("BrowsingData.removeIndexedDB", since);
	    } catch (Exception ignored) {
	    }
	    try {
		JsonObject since = new JsonObject();
		since.addProperty("since", SINCE_ALL_TIME);
		cdp.send("BrowsingData.removeCacheStorage", since);
	    } catch (Exception ignored) {
	    }
	    try {
		JsonObject since = new JsonObject();
		since.addProperty("since", SINCE_ALL_TIME);
		cdp.send("BrowsingData.removeServiceWorkers", since);
	    } catch (Exception ignored) {
	    }
	}
    }

    /**
     * Matches Chrome checkbox "Cached images and files" with Time range = All time.
     */
    private static void clearCachedImagesAndFilesAllTime(CDPSession cdp, String when) {
	try {
	    JsonObject since = new JsonObject();
	    since.addProperty("since", SINCE_ALL_TIME);
	    cdp.send("BrowsingData.removeCache", since);
	    log.info("BrowsingData.removeCache (cached images/files, All time) at {}", when);
	} catch (Exception e) {
	    log.warn("BrowsingData.removeCache failed at {}: {} — using Network.clearBrowserCache",
		    when, e.getMessage());
	}
	try {
	    // Full HTTP disk cache wipe for this browser instance
	    cdp.send("Network.clearBrowserCache");
	    log.info("Network.clearBrowserCache (All time) at {}", when);
	} catch (Exception e) {
	    log.warn("Network.clearBrowserCache failed at {}: {}", when, e.getMessage());
	}
    }

    private static void clearOriginStorageViaCdp(CDPSession cdp, Page page, String when) {
	try {
	    String url = page.url();
	    if (url == null || url.isBlank() || url.startsWith("about:") || url.startsWith("chrome:")) {
		return;
	    }
	    URI uri = URI.create(url);
	    if (uri.getScheme() == null || uri.getAuthority() == null) {
		return;
	    }
	    String origin = uri.getScheme() + "://" + uri.getAuthority();
	    JsonObject params = new JsonObject();
	    params.addProperty("origin", origin);
	    params.addProperty("storageTypes", "all");
	    cdp.send("Storage.clearDataForOrigin", params);
	    log.info("Cleared Storage.clearDataForOrigin for {} at {}", origin, when);
	} catch (Exception e) {
	    log.warn("Storage.clearDataForOrigin failed at {}: {}", when, e.getMessage());
	}
    }

    private static void clearPageStorageJs(Page page, String when) {
	try {
	    page.evaluate("""
		    async () => {
		      try { localStorage.clear(); } catch (e) {}
		      try { sessionStorage.clear(); } catch (e) {}
		      try {
		        if (window.caches && caches.keys) {
		          const keys = await caches.keys();
		          await Promise.all(keys.map(k => caches.delete(k)));
		        }
		      } catch (e) {}
		      try {
		        if (window.indexedDB && indexedDB.databases) {
		          const dbs = await indexedDB.databases();
		          await Promise.all((dbs || []).map(db => {
		            if (!db || !db.name) return Promise.resolve();
		            return new Promise(res => {
		              const r = indexedDB.deleteDatabase(db.name);
		              r.onsuccess = r.onerror = r.onblocked = () => res();
		            });
		          }));
		        }
		      } catch (e) {}
		    }
		    """);
	    log.info("Cleared page JS storage at {}", when);
	} catch (Exception e) {
	    log.warn("Page JS storage clear failed at {}: {}", when, e.getMessage());
	}
    }
}
