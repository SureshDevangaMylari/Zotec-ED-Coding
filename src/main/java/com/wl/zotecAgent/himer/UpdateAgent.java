package com.wl.zotecAgent.himer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.SystemPropertyUtils;
import org.springframework.web.client.RestTemplate;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import jakarta.annotation.PostConstruct;

public class UpdateAgent {

    static final String LOGIN_URL = "http://10.1.240.225:8001/api/auth/login";

    static final String AGENT_URL = "http://10.1.240.225:8001/api/agents/698ae5c9b0bf82d7668c29c8";
    public static String Bulkid = "";

    volatile String token;
    volatile boolean botRunning = false;

    // 🔐 Login once on startup

    // ---------- GET AGENT ----------

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    // ---------- BOT ----------
    private void startBot() throws Exception {
	System.out.println("🚀 Bot STARTED");

	// your bot start logic
	playwright = Playwright.create();

	BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setChannel("chrome")
		.setHeadless(false).setArgs(List.of("--start-maximized"));

	browser = playwright.chromium().launch(launchOptions);

	Browser.NewContextOptions contextOptions = new Browser.NewContextOptions().setAcceptDownloads(true)
		.setViewportSize(null);

	context = browser.newContext(contextOptions);

	page = context.newPage();

	// Start your flow here
//	Flow.Start(context);
    }

    private void stopBot() {
	System.out.println("🛑 Bot STOPPED");
	if (page != null)
	    page.close();
	if (context != null)
	    context.close();
	if (browser != null)
	    browser.close();
	if (playwright != null)
	    playwright.close();
	// your bot stop logic
    }

    private static final String AGENT_LOGS_BASE_URL = "http://10.1.240.225:8001/api/agent-logs";
    private static final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
	    .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public static String updateAgentStage(String agentObjectId, int step, long recordId, String status)
	    throws Exception {
//	String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZCI6IjY5OGFkYmQ1YzJjMWZiZWU2NDFkYWQ1NyIsImVtYWlsIjoidW1lc2gua2F0YWthbUB3YXRlcmxhYnMuYWkiLCJyb2xlIjoidXNlciIsImNsaWVudElkIjoiNjk4MWQyMjExNjg3YWJkYzIyNjBkMmRmIiwiaWF0IjoxNzczMDUxNjUxLCJleHAiOjE3NzMyMjQ0NTF9.G_4TMh1eadriJi6dT6H4Y26bRhasep06Kv1AbiegZpQ"; // ✅

	// NOW
	String token = AgentPollingService.token;
	// WORKS
	// PERFECTLY

	String fullUrl = AGENT_LOGS_BASE_URL + "/" + agentObjectId + "/stages";
	String jsonBody = String.format("{\"step\": %d, \"status\": \"%s\", \"recordId\": \"%s\"}", step, status,
		recordId);

	HttpRequest request = HttpRequest.newBuilder().uri(URI.create(fullUrl))
		.method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
		.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
		.header("User-Agent", "AgentService/5.2").timeout(Duration.ofSeconds(15)).build();

	System.out.println(" PATCH Step " + step + " → " + status + " | Agent: " + agentObjectId);
	System.out.println(" Body: " + jsonBody);

	HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
	System.out.println(" Status: " + response.statusCode());
	System.out.println("✅ Response: " + response.body());

	if (response.statusCode() >= 200 && response.statusCode() < 300) {
	    System.out.println("✅ Step " + step + " " + status + " COMPLETED! ");
	    return response.body();
	} else {
	    throw new RuntimeException("PATCH failed: " + response.statusCode() + " - " + response.body());
	}
    }

    public void updateRecordStages1(String agentId, long recordId, int step, String status)
	    throws IOException, InterruptedException {
//	String url = "http://10.1.240.225:8001/api/bulk-data/update-record-status";

	String url = "http://10.1.240.225:8001/api/agent-logs/" + agentId + "/stages";
//	int step = 2;
//	String status = "ERROR";
//	String recordId = "adsfdgfsdartghga";

	String body = String.format("{\"step\": %d, \"status\": \"%s\", \"recordId\": \"%s\"}", step, status,
		recordId);
	HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

//	String token = AgentPollingService.token;
	String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZCI6IjY5OGFkYmQ1YzJjMWZiZWU2NDFkYWQ1NyIsImVtYWlsIjoidW1lc2gua2F0YWthbUB3YXRlcmxhYnMuYWkiLCJyb2xlIjoidXNlciIsImNsaWVudElkIjoiNjk4MWQyMjExNjg3YWJkYzIyNjBkMmRmIiwiaWF0IjoxNzczMDUxNjUxLCJleHAiOjE3NzMyMjQ0NTF9.G_4TMh1eadriJi6dT6H4Y26bRhasep06Kv1AbiegZpQ";
	HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
		.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
		.header("Accept", "application/json").header("User-Agent", "Java-HttpClient/1.0")
		.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();

	try {
	    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
	    System.out.println("Status: " + response.statusCode());
	    System.out.println("Response: " + response.body());
	} catch (IOException e) {
	    System.err.println("HTTP request failed to " + url);
	    System.err.println(
		    "Possible causes: server not running, network unreachable, firewall blocking, or server closed connection.");
	    throw e;
	}

    }

    public static void main(String[] args) throws Exception {
	long id = 123;
	UpdateAgent u = new UpdateAgent();
	u.updateRecordStages1("69ae9c1838dc818f286b2957", id,1, "COMPLETED");
//	u.updateAgentStage("69ae9c1838dc818f286b2957", id, 1, "COMPLETED");
    }
}
