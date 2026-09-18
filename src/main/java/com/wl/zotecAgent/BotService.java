package com.wl.zotecAgent;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final Flow flow;
    private final FlowText flowText;

    @Value("${flow.agent-id:698ae5c9b0bf82d7668c29c8}")
    private String defaultAgentId;

    /** Empty = default OS Chrome User Data (extensions / cookies from normal Chrome). */
    @Value("${zotec.chrome.user-data-dir:}")
    private String chromeUserDataDir;

    @Value("${zotec.chrome.profile-directory:Default}")
    private String chromeProfileDirectory;

    @Value("${zotec.chrome.debugging-port:9222}")
    private int chromeDebuggingPort;

    /** Optional override, e.g. /usr/bin/google-chrome-stable on Ubuntu. */
    @Value("${zotec.chrome.executable:}")
    private String chromeExecutable;

    private Thread botThread;
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private Process chromeProcess;

    private volatile String activeChartType;
    private volatile List<String> activeClients = List.of();

    public BotService(Flow flow, FlowText flowText) {
	this.flow = flow;
	this.flowText = flowText;
    }

    public boolean isRunning() {
	return running;
    }

    public boolean isStopRequested() {
	return stopRequested;
    }

    public String getActiveChartType() {
	return activeChartType;
    }

    public List<String> getActiveClients() {
	return activeClients == null ? List.of() : List.copyOf(activeClients);
    }

    /**
     * Legacy entry (e.g. FlowStartupRunner): defaults to FlowText with full AllowedClients.
     */
    public void startBot(List<?> data, String bulkId, String agentId) {
	startBotFromUi("Text", AllowedClients.orderedEntries(), agentId);
    }

    /**
     * Start from UI JSON: chartType Text → {@link FlowText}, Image → {@link Flow}.
     * Only the given client labels are walked in the Zotec Select client(s) panel.
     */
    public synchronized boolean startBotFromUi(String chartType, List<String> clients) {
	return startBotFromUi(chartType, clients, defaultAgentId);
    }

    public synchronized boolean startBotFromUi(String chartType, List<String> clients, String agentId) {
	if (running) {
	    log.warn("Bot already running");
	    return false;
	}
	if (clients == null || clients.isEmpty()) {
	    log.warn("No clients provided — refuse start");
	    return false;
	}

	final String type = chartType != null ? chartType.trim() : "Text";
	final List<String> selected = Collections.unmodifiableList(new ArrayList<>(clients));
	final String aid = (agentId != null && !agentId.isBlank()) ? agentId : defaultAgentId;

	running = true;
	stopRequested = false;
	activeChartType = type;
	activeClients = selected;

	botThread = new Thread(() -> {
	    try {
		log.info("Bot STARTED chartType={} clients={}", type, selected.size());

		playwright = Playwright.create();
		launchChromeWithUserProfileAndConnect();

		log.info("Logging into Zotec and continuing Flow (chartType={})", type);
		if ("Image".equalsIgnoreCase(type)) {
		    flow.Start(context, aid, selected);
		} else {
		    flowText.Start(context, aid, selected);
		}
		Thread.sleep(1000);
	    } catch (Exception e) {
		if (!stopRequested) {
		    log.error("Bot run failed", e);
		} else {
		    log.info("Bot stopped: {}", e.getMessage());
		}
	    } finally {
		cleanup();
		running = false;
		stopRequested = false;
		activeChartType = null;
		activeClients = List.of();
		log.info("Bot Thread Exited");
	    }
	}, "zotec-bot");

	botThread.start();
	return true;
    }

    /**
     * Starts OS Chrome with the normal user profile (extensions load), attaches via CDP,
     * then caller runs {@link Flow} / {@link FlowText} (Zotec login → clients → coding).
     */
    private void launchChromeWithUserProfileAndConnect() throws Exception {
	Path userData = resolveChromeUserDataDir();
	if (!Files.isDirectory(userData)) {
	    throw new IllegalStateException(
		    "Chrome user-data-dir not found: " + userData.toAbsolutePath()
			    + " — set zotec.chrome.user-data-dir in application.properties");
	}

	String profile = (chromeProfileDirectory != null && !chromeProfileDirectory.isBlank())
		? chromeProfileDirectory.trim()
		: "Default";
	Path chromeExe = resolveChromeExecutable();
	int port = chromeDebuggingPort > 0 ? chromeDebuggingPort : 9222;
	String cdpUrl = "http://127.0.0.1:" + port;

	if (isCdpReady(cdpUrl)) {
	    log.info("CDP already available at {} — attaching", cdpUrl);
	    attachPlaywrightToCdp(cdpUrl);
	    return;
	}

	ensureChromeClosedForProfileLaunch(userData);

	List<String> chromeArgs = new ArrayList<>();
	chromeArgs.add("--remote-debugging-port=" + port);
	chromeArgs.add("--remote-allow-origins=*");
	chromeArgs.add("--user-data-dir=" + userData.toAbsolutePath());
	chromeArgs.add("--profile-directory=" + profile);
	chromeArgs.add("--start-maximized");
	chromeArgs.add("--no-first-run");
	chromeArgs.add("--no-default-browser-check");

	log.info("Starting OS Chrome (detached) exe={} userDataDir={} profile={} cdp={}",
		chromeExe, userData.toAbsolutePath(), profile, cdpUrl);

	startChromeDetached(chromeExe, chromeArgs);

	// Attach as soon as CDP is up, then caller starts Zotec login immediately
	waitForCdpReady(cdpUrl, 45_000);
	attachPlaywrightToCdp(cdpUrl);
	log.info("Chrome attached — handing off to Zotec login / Flow");
    }

    /**
     * Launch Chrome via PowerShell Start-Process so remote debugging binds (same as a
     * normal desktop launch). Direct ProcessBuilder child + piped I/O often never opens CDP.
     */
    private void startChromeDetached(Path chromeExe, List<String> chromeArgs) throws Exception {
	if (isWindows()) {
	    StringBuilder argList = new StringBuilder();
	    for (int i = 0; i < chromeArgs.size(); i++) {
		if (i > 0) {
		    argList.append(',');
		}
		argList.append('\'').append(chromeArgs.get(i).replace("'", "''")).append('\'');
	    }
	    String exe = chromeExe.toAbsolutePath().toString().replace("'", "''");
	    String ps = "Start-Process -FilePath '" + exe + "' -ArgumentList @(" + argList + ")";
	    ProcessBuilder pb = new ProcessBuilder(
		    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps);
	    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
	    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
	    Process launcher = pb.start();
	    launcher.waitFor(20, TimeUnit.SECONDS);
	    chromeProcess = null;
	    return;
	}

	// Linux: detach via setsid so Chrome is not a stuck JVM child
	List<String> cmd = new ArrayList<>();
	cmd.add("setsid");
	cmd.add(chromeExe.toAbsolutePath().toString());
	cmd.addAll(chromeArgs);
	ProcessBuilder pb = new ProcessBuilder(cmd);
	pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
	pb.redirectError(ProcessBuilder.Redirect.DISCARD);
	try {
	    chromeProcess = pb.start();
	} catch (Exception setsidMissing) {
	    // setsid may be absent — fall back to direct start
	    List<String> fallback = new ArrayList<>();
	    fallback.add(chromeExe.toAbsolutePath().toString());
	    fallback.addAll(chromeArgs);
	    chromeProcess = new ProcessBuilder(fallback)
		    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
		    .redirectError(ProcessBuilder.Redirect.DISCARD)
		    .start();
	}
    }

    private void attachPlaywrightToCdp(String cdpUrl) {
	browser = playwright.chromium().connectOverCDP(cdpUrl);
	if (browser.contexts().isEmpty()) {
	    throw new IllegalStateException("Chrome CDP connected but no browser contexts available");
	}
	context = browser.contexts().get(0);
	if (!context.pages().isEmpty()) {
	    page = context.pages().get(0);
	} else {
	    page = context.newPage();
	}
	log.info("Playwright attached to Chrome (pages={})", context.pages().size());
    }

    /**
     * Chrome only enables remote debugging when it is the process that owns the profile.
     * A normal Chrome already open with the same User Data dir causes our launch to never
     * open port 9222 (Connection refused → bot times out and exits).
     */
    private void ensureChromeClosedForProfileLaunch(Path userData) throws InterruptedException {
	log.info("Closing existing Chrome so profile can start with CDP (profile data stays intact)");
	try {
	    if (isWindows()) {
		Process kill = new ProcessBuilder("taskkill", "/F", "/IM", "chrome.exe", "/T")
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.start();
		kill.waitFor(15, TimeUnit.SECONDS);
	    } else {
		for (String pattern : List.of("chrome", "google-chrome", "chromium", "chromium-browser")) {
		    try {
			new ProcessBuilder("pkill", "-f", pattern)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start()
				.waitFor(5, TimeUnit.SECONDS);
		    } catch (Exception ignored) {
		    }
		}
	    }
	} catch (Exception e) {
	    log.debug("kill chrome: {}", e.getMessage());
	}
	try {
	    Files.deleteIfExists(userData.resolve("SingletonLock"));
	    Files.deleteIfExists(userData.resolve("SingletonCookie"));
	    Files.deleteIfExists(userData.resolve("SingletonSocket"));
	} catch (Exception e) {
	    log.debug("clear Singleton*: {}", e.getMessage());
	}
	Thread.sleep(2000);
    }

    private boolean isCdpReady(String cdpUrl) {
	try {
	    HttpURLConnection conn = (HttpURLConnection) URI.create(cdpUrl + "/json/version").toURL()
		    .openConnection();
	    conn.setConnectTimeout(1000);
	    conn.setReadTimeout(1000);
	    conn.connect();
	    int code = conn.getResponseCode();
	    conn.disconnect();
	    return code >= 200 && code < 500;
	} catch (Exception e) {
	    return false;
	}
    }

    private void waitForCdpReady(String cdpUrl, long timeoutMs) throws InterruptedException {
	long deadline = System.currentTimeMillis() + timeoutMs;
	int attempts = 0;
	while (System.currentTimeMillis() < deadline) {
	    if (stopRequested) {
		throw new IllegalStateException("Stop requested while waiting for Chrome CDP");
	    }
	    // Detached launch: launcher exits immediately; do not treat that as failure
	    if (chromeProcess != null && !chromeProcess.isAlive()) {
		log.debug("Tracked chrome launcher exited (code={}); still waiting for CDP",
			chromeProcess.exitValue());
		chromeProcess = null;
	    }
	    if (isCdpReady(cdpUrl)) {
		return;
	    }
	    attempts++;
	    if (attempts == 1 || attempts % 10 == 0) {
		log.info("Waiting for Chrome CDP at {} …", cdpUrl);
	    }
	    Thread.sleep(500);
	}
	throw new IllegalStateException("Timed out waiting for Chrome CDP at " + cdpUrl
		+ ". Port never opened — close Chrome fully and retry, or check port "
		+ chromeDebuggingPort + " is free.");
    }

    private Path resolveChromeExecutable() {
	if (chromeExecutable != null && !chromeExecutable.isBlank()) {
	    Path configured = Paths.get(chromeExecutable.trim());
	    if (Files.isRegularFile(configured) || Files.isExecutable(configured)) {
		return configured;
	    }
	    throw new IllegalStateException("zotec.chrome.executable not found: " + configured.toAbsolutePath());
	}

	List<Path> candidates = new ArrayList<>();
	if (isWindows()) {
	    String pf = System.getenv("ProgramFiles");
	    String pf86 = System.getenv("ProgramFiles(x86)");
	    String local = System.getenv("LOCALAPPDATA");
	    if (pf != null) {
		candidates.add(Paths.get(pf, "Google", "Chrome", "Application", "chrome.exe"));
	    }
	    if (pf86 != null) {
		candidates.add(Paths.get(pf86, "Google", "Chrome", "Application", "chrome.exe"));
	    }
	    if (local != null) {
		candidates.add(Paths.get(local, "Google", "Chrome", "Application", "chrome.exe"));
	    }
	    candidates.add(Paths.get("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"));
	    candidates.add(Paths.get("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"));
	} else if (isMac()) {
	    candidates.add(Paths.get("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
	    candidates.add(Paths.get("/Applications/Chromium.app/Contents/MacOS/Chromium"));
	} else {
	    // Ubuntu / Linux
	    candidates.add(Paths.get("/usr/bin/google-chrome"));
	    candidates.add(Paths.get("/usr/bin/google-chrome-stable"));
	    candidates.add(Paths.get("/usr/bin/chromium-browser"));
	    candidates.add(Paths.get("/usr/bin/chromium"));
	    candidates.add(Paths.get("/snap/bin/chromium"));
	    String pathEnv = System.getenv("PATH");
	    if (pathEnv != null) {
		for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
		    if (dir.isBlank()) {
			continue;
		    }
		    candidates.add(Paths.get(dir, "google-chrome"));
		    candidates.add(Paths.get(dir, "google-chrome-stable"));
		    candidates.add(Paths.get(dir, "chromium-browser"));
		    candidates.add(Paths.get(dir, "chromium"));
		}
	    }
	}
	for (Path p : candidates) {
	    if (Files.isRegularFile(p)) {
		return p;
	    }
	}
	throw new IllegalStateException(
		"Chrome/Chromium executable not found — install Google Chrome or set zotec.chrome.executable");
    }

    /**
     * Default OS Chrome profile directory:
     * Windows {@code %LOCALAPPDATA%\Google\Chrome\User Data},
     * Linux {@code ~/.config/google-chrome} (or chromium),
     * macOS {@code ~/Library/Application Support/Google/Chrome}.
     */
    private Path resolveChromeUserDataDir() {
	if (chromeUserDataDir != null && !chromeUserDataDir.isBlank()) {
	    return Paths.get(chromeUserDataDir.trim());
	}
	String home = System.getProperty("user.home");
	if (home == null || home.isBlank()) {
	    home = ".";
	}
	List<Path> candidates = new ArrayList<>();
	if (isWindows()) {
	    String localAppData = System.getenv("LOCALAPPDATA");
	    if (localAppData == null || localAppData.isBlank()) {
		localAppData = home + File.separator + "AppData" + File.separator + "Local";
	    }
	    candidates.add(Paths.get(localAppData, "Google", "Chrome", "User Data"));
	} else if (isMac()) {
	    candidates.add(Paths.get(home, "Library", "Application Support", "Google", "Chrome"));
	    candidates.add(Paths.get(home, "Library", "Application Support", "Chromium"));
	} else {
	    // Linux (Ubuntu VM): never use Windows AppData paths
	    candidates.add(Paths.get(home, ".config", "google-chrome"));
	    candidates.add(Paths.get(home, ".config", "chromium"));
	    candidates.add(Paths.get(home, "snap", "chromium", "common", "chromium"));
	}
	for (Path p : candidates) {
	    if (Files.isDirectory(p)) {
		return p;
	    }
	}
	// Return the primary expected path so the error message is actionable
	return candidates.get(0);
    }

    private static boolean isWindows() {
	String os = System.getProperty("os.name", "");
	return os.toLowerCase().contains("win");
    }

    private static boolean isMac() {
	String os = System.getProperty("os.name", "");
	return os.toLowerCase().contains("mac");
    }

    /** Stop Playwright / Chrome; does not clear browsing data. */
    public synchronized void stopBot() {
	log.info("Bot STOP requested");
	stopRequested = true;
	try {
	    if (page != null) {
		page.close();
	    }
	    if (browser != null) {
		browser.close();
	    }
	    if (playwright != null) {
		playwright.close();
	    }
	} catch (Exception e) {
	    log.debug("stopBot close: {}", e.getMessage());
	} finally {
	    destroyChromeProcess();
	    page = null;
	    context = null;
	    browser = null;
	    playwright = null;
	    if (botThread != null) {
		botThread.interrupt();
	    }
	    running = false;
	    activeChartType = null;
	    activeClients = List.of();
	}
    }

    void cleanup() {
	try {
	    // No BrowserCacheClearer — keep cookies/extensions for the real Chrome profile
	    if (browser != null) {
		browser.close();
	    }
	    if (playwright != null) {
		playwright.close();
	    }
	} catch (Exception e) {
	    log.debug("cleanup: {}", e.getMessage());
	} finally {
	    destroyChromeProcess();
	    page = null;
	    context = null;
	    browser = null;
	    playwright = null;
	}
    }

    private void destroyChromeProcess() {
	try {
	    if (chromeProcess != null) {
		chromeProcess.destroy();
		if (!chromeProcess.waitFor(5, TimeUnit.SECONDS)) {
		    chromeProcess.destroyForcibly();
		}
	    }
	} catch (Exception e) {
	    log.debug("destroyChromeProcess: {}", e.getMessage());
	} finally {
	    chromeProcess = null;
	}
	try {
	    if (isWindows()) {
		Process kill = new ProcessBuilder("taskkill", "/F", "/IM", "chrome.exe", "/T")
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.start();
		kill.waitFor(10, TimeUnit.SECONDS);
	    } else {
		for (String pattern : List.of("chrome", "google-chrome", "chromium", "chromium-browser")) {
		    try {
			new ProcessBuilder("pkill", "-f", pattern)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start()
				.waitFor(5, TimeUnit.SECONDS);
		    } catch (Exception ignored) {
		    }
		}
	    }
	} catch (Exception e) {
	    log.debug("kill chrome on destroy: {}", e.getMessage());
	}
    }
}
