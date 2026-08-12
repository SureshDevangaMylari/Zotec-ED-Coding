package com.wl.zotecAgent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    public void updateRecordStatus(String bulkId, String agentId, String recordId, String id, String status)
	    throws IOException, InterruptedException {
	String url = "http://10.1.240.225:8001/api/bulk-data/update-record-status";
	String bodyJson = "{\"bulk_id\":\"" + bulkId + "\",\"agent_id\":\"" + agentId + "\",\"id\":\"" + recordId
		+ "\",\"status\":\"" + status + "\",\"recordId\":\"" + recordId + "\",\"error\":{}}";

	HttpClient client = HttpClient.newHttpClient();

	String token =  AgentPollingService.token;
	HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer " + token) // add
														       // Bearer
		.header("Content-Type", "application/json")
		.method("PATCH", HttpRequest.BodyPublishers.ofString(bodyJson)).build();

	HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

	System.out.println("Status: " + response.statusCode());
	System.out.println("Response: " + response.body());

    }

}
