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

    /** Empty = default OS Chrome profile (source for sync / Windows CDP). */
    @Value("${zotec.chrome.user-data-dir:}")
    private String chromeUserDataDir;

    @Value("${zotec.chrome.profile-directory:Default}")
    private String chromeProfileDirectory;

    @Value("${zotec.chrome.debugging-port:9222}")
    private int chromeDebuggingPort;

    /** Optional override, e.g. /usr/bin/google-chrome-stable on Ubuntu. */
    @Value("${zotec.chrome.executable:}")
    private String chromeExecutable;

    @Value("${zotec.portal.URL:https://coding.zotecpartners.com/}")
    private String zotecPortalUrl;

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
     * Windows: real Chrome User Data + detached CDP.
     * Linux: sync real profile → non-default agent dir + CDP (persistent context hangs on
     * ~/.config/google-chrome; CDP is ignored on that default path).
     */
    private void launchChromeWithUserProfile() throws Exception {
	Path sourceUserData = resolveChromeUserDataDir();
	if (!Files.isDirectory(sourceUserData)) {
	    throw new IllegalStateException(
		    "Chrome user-data-dir not found: " + sourceUserData.toAbsolutePath()
			    + " — set zotec.chrome.user-data-dir in application.properties");
	}

	String profile = (chromeProfileDirectory != null && !chromeProfileDirectory.isBlank())
		? chromeProfileDirectory.trim()
		: "Default";
	Path chromeExe = resolveChromeExecutable();

	ensureChromeClosedForProfileLaunch(sourceUserData);

	if (isWindows()) {
	    launchViaCdp(chromeExe, sourceUserData, profile, "Windows");
	} else {
	    requireDisplayForLinux();
	    Path agentUserData = resolveLinuxAgentUserDataDir();
	    syncChromeProfileForLinux(sourceUserData, agentUserData);
	    ensureChromeClosedForProfileLaunch(agentUserData);
	    launchViaCdp(chromeExe, agentUserData, profile, "Linux");
	}
    }

    private void requireDisplayForLinux() {
	String display = System.getenv("DISPLAY");
	if (display == null || display.isBlank()) {
	    throw new IllegalStateException(
		    "DISPLAY is not set. On Ubuntu the bot needs a GUI/VNC session "
			    + "(e.g. export DISPLAY=:0). Playwright deps alone are not enough.");
	}
	log.info("Linux DISPLAY={}", display);
    }

    /**
     * Non-default profile path so Chrome enables --remote-debugging-port on Linux.
     */
    private Path resolveLinuxAgentUserDataDir() throws Exception {
	String home = System.getProperty("user.home", ".");
	Path agent = Paths.get(home, ".config", "zotec-agent-chrome");
	Files.createDirectories(agent);
	return agent;
    }

    /**
     * Copy cookies/extensions/session from normal Chrome into the agent profile dir.
     */
    private void syncChromeProfileForLinux(Path source, Path dest) throws Exception {
	log.info("Syncing Chrome profile {} → {} (so extensions/session work with CDP)",
		source.toAbsolutePath(), dest.toAbsolutePath());
	ProcessBuilder pb = new ProcessBuilder(
		"rsync", "-a",
		"--delete",
		"--exclude=SingletonLock",
		"--exclude=SingletonCookie",
		"--exclude=SingletonSocket",
		"--exclude=Crashpad",
		"--exclude=BrowserMetrics",
		"--exclude=ShaderCache",
		"--exclude=GrShaderCache",
		"--exclude=GraphiteDawnCache",
		source.toAbsolutePath() + "/",
		dest.toAbsolutePath() + "/");
	pb.redirectErrorStream(true);
	Process p = pb.start();
	boolean finished = p.waitFor(10, TimeUnit.MINUTES);
	if (!finished) {
	    p.destroyForcibly();
	    throw new IllegalStateException("Timed out syncing Chrome profile with rsync");
	}
	if (p.exitValue() != 0) {
	    // Fallback: cp -a if rsync missing
	    log.warn("rsync exit={} — trying cp -a", p.exitValue());
	    ProcessBuilder cp = new ProcessBuilder("bash", "-lc",
		    "mkdir -p '" + dest.toAbsolutePath() + "' && "
			    + "cp -a '" + source.toAbsolutePath() + "/.' '" + dest.toAbsolutePath() + "/'");
	    cp.redirectErrorStream(true);
	    Process cpProc = cp.start();
	    if (!cpProc.waitFor(10, TimeUnit.MINUTES) || cpProc.exitValue() != 0) {
		throw new IllegalStateException(
			"Could not sync Chrome profile (install rsync: sudo apt-get install -y rsync)");
	    }
	}
	Files.deleteIfExists(dest.resolve("SingletonLock"));
	Files.deleteIfExists(dest.resolve("SingletonCookie"));
	Files.deleteIfExists(dest.resolve("SingletonSocket"));
	log.info("Chrome profile sync complete");
    }

    private void launchViaCdp(Path chromeExe, Path userData, String profile, String osLabel)
	    throws Exception {
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
	//chromeArgs.add("--no-first-run");
	chromeArgs.add("--no-default-browser-check");
	if (!isWindows()) {
	    chromeArgs.add("--disable-dev-shm-usage");
	    chromeArgs.add("--no-sandbox");
	}

	log.info("Starting {} Chrome (detached + CDP) exe={} userDataDir={} profile={} cdp={}",
		osLabel, chromeExe, userData.toAbsolutePath(), profile, cdpUrl);

	if (isWindows()) {
	    startChromeDetachedWindows(chromeExe, chromeArgs);
	} else {
	    startChromeDetachedUnix(chromeExe, chromeArgs);
	}

	waitForCdpReady(cdpUrl, 60_000);
	attachPlaywrightToCdp(cdpUrl);
	log.info("Chrome attached via CDP — continuing to Zotec login / Flow");
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

    private void startChromeDetachedUnix(Path chromeExe, List<String> chromeArgs) throws Exception {
	List<String> cmd = new ArrayList<>();
	cmd.add("setsid");
	cmd.add(chromeExe.toAbsolutePath().toString());
	cmd.addAll(chromeArgs);
	ProcessBuilder pb = new ProcessBuilder(cmd);
	pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
	pb.redirectError(ProcessBuilder.Redirect.DISCARD);
	try {
	    chromeProcess = pb.start();
	} catch (Exception e) {
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
	    if (chromeProcess != null && !chromeProcess.isAlive()) {
		log.warn("Chrome process exited early (code={}) while waiting for CDP",
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
		+ ". Check DISPLAY, close Chrome, and retry.");
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
	    clearZotecCookiesBeforeClose("stopBot");
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
	    clearZotecCookiesBeforeClose("cleanup");
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

    /** Drop Zotec session from the Chrome profile before the browser process exits. */
    private void clearZotecCookiesBeforeClose(String when) {
	try {
	    if (context == null) {
		return;
	    }
	    Page p = page;
	    if (p == null || p.isClosed()) {
		if (!context.pages().isEmpty()) {
		    p = context.pages().get(0);
		}
	    }
	    BrowserCacheClearer.clearZotecSiteOnly(context, p, zotecPortalUrl, when);
	} catch (Exception e) {
	    log.debug("clearZotecCookiesBeforeClose at {}: {}", when, e.getMessage());
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
		for (String pattern : List.of("chrome", "google-chrome", "chromium")) {
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
