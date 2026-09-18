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
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final Flow flow;
    private final FlowText flowText;

    @Value("${flow.agent-id:698ae5c9b0bf82d7668c29c8}")
    private String defaultAgentId;

    /** Empty = default OS Chrome profile (extensions / cookies from normal Chrome). */
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

    public void startBot(List<?> data, String bulkId, String agentId) {
	startBotFromUi("Text", AllowedClients.orderedEntries(), agentId);
    }

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
		launchChromeWithUserProfile();

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
     * Windows: detached Chrome + CDP (launchPersistentContext hangs on real User Data).
     * Linux/macOS: Playwright launchPersistentContext (Chrome ignores CDP on default profile).
     * Then caller continues to Zotec login / Flow.
     */
    private void launchChromeWithUserProfile() throws Exception {
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

	ensureChromeClosedForProfileLaunch(userData);

	if (isWindows()) {
	    launchWindowsViaCdp(chromeExe, userData, profile);
	} else {
	    launchViaPersistentContext(chromeExe, userData, profile);
	}
    }

    /**
     * Windows-only: Start-Process Chrome with remote debugging, then connectOverCDP.
     * Does not use launchPersistentContext (that call never returns on real User Data).
     */
    private void launchWindowsViaCdp(Path chromeExe, Path userData, String profile) throws Exception {
	int port = chromeDebuggingPort > 0 ? chromeDebuggingPort : 9222;
	String cdpUrl = "http://127.0.0.1:" + port;

	if (isCdpReady(cdpUrl)) {
	    log.info("CDP already available at {} — attaching", cdpUrl);
	    attachPlaywrightToCdp(cdpUrl);
	    return;
	}

	List<String> chromeArgs = new ArrayList<>();
	chromeArgs.add("--remote-debugging-port=" + port);
	chromeArgs.add("--remote-allow-origins=*");
	chromeArgs.add("--user-data-dir=" + userData.toAbsolutePath());
	chromeArgs.add("--profile-directory=" + profile);
	chromeArgs.add("--start-maximized");
	chromeArgs.add("--no-first-run");
	chromeArgs.add("--no-default-browser-check");

	log.info("Starting Windows Chrome (detached + CDP) exe={} userDataDir={} profile={} cdp={}",
		chromeExe, userData.toAbsolutePath(), profile, cdpUrl);
	startChromeDetachedWindows(chromeExe, chromeArgs);

	waitForCdpReady(cdpUrl, 45_000);
	attachPlaywrightToCdp(cdpUrl);
	log.info("Chrome attached via CDP — continuing to Zotec login / Flow");
    }

    private void launchViaPersistentContext(Path chromeExe, Path userData, String profile) {
	List<String> args = new ArrayList<>();
	args.add("--profile-directory=" + profile);
	args.add("--start-maximized");
	args.add("--no-first-run");
	args.add("--no-default-browser-check");
	args.add("--disable-dev-shm-usage");
	args.add("--no-sandbox");

	log.info("Starting Chrome via Playwright persistent context exe={} userDataDir={} profile={}",
		chromeExe, userData.toAbsolutePath(), profile);

	BrowserType.LaunchPersistentContextOptions opts = new BrowserType.LaunchPersistentContextOptions()
		.setHeadless(false)
		.setExecutablePath(chromeExe)
		.setIgnoreDefaultArgs(List.of("--disable-extensions"))
		.setArgs(args)
		.setViewportSize(null);

	context = playwright.chromium().launchPersistentContext(userData, opts);
	browser = context.browser();
	if (!context.pages().isEmpty()) {
	    page = context.pages().get(0);
	} else {
	    page = context.newPage();
	}
	log.info("Chrome ready (pages={}) — continuing to Zotec login / Flow", context.pages().size());
    }

    private void startChromeDetachedWindows(Path chromeExe, List<String> chromeArgs) throws Exception {
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

    private void ensureChromeClosedForProfileLaunch(Path userData) throws InterruptedException {
	log.info("Closing existing Chrome so profile can start (profile data stays intact)");
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
	Thread.sleep(1500);
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
		+ ". Close Chrome fully and retry.");
    }

    private Path resolveChromeExecutable() {
	if (chromeExecutable != null && !chromeExecutable.isBlank()) {
	    Path configured = Paths.get(chromeExecutable.trim());
	    if (Files.isRegularFile(configured)) {
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
	    candidates.add(Paths.get(home, ".config", "google-chrome"));
	    candidates.add(Paths.get(home, ".config", "chromium"));
	    candidates.add(Paths.get(home, "snap", "chromium", "common", "chromium"));
	}
	for (Path p : candidates) {
	    if (Files.isDirectory(p)) {
		return p;
	    }
	}
	return candidates.get(0);
    }

    private static boolean isWindows() {
	return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isMac() {
	return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    public synchronized void stopBot() {
	log.info("Bot STOP requested");
	stopRequested = true;
	try {
	    if (context != null) {
		context.close();
	    } else if (browser != null) {
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
	    if (context != null) {
		context.close();
	    } else if (browser != null) {
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
	if (!isWindows()) {
	    return;
	}
	try {
	    Process kill = new ProcessBuilder("taskkill", "/F", "/IM", "chrome.exe", "/T")
		    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
		    .redirectError(ProcessBuilder.Redirect.DISCARD)
		    .start();
	    kill.waitFor(10, TimeUnit.SECONDS);
	} catch (Exception e) {
	    log.debug("taskkill on destroy: {}", e.getMessage());
	}
    }
}
