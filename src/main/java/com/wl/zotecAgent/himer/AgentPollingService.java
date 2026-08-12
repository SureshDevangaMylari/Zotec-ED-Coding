package com.wl.zotecAgent.himer;

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

@Service
public class AgentPollingService {

    @Autowired
    BotService Bot;
    static final String LOGIN_URL = "http://10.1.240.225:8001/api/auth/login";

    static final String AGENT_URL = "http://10.1.240.225:8001/api/agents/69ae9c1838dc818f286b2957";
    static final String agentId = "69ae9c1838dc818f286b2957";
    public static String Bulkid = "";
    final RestTemplate restTemplate;

    public static String token;
    volatile boolean botRunning = false;

    public AgentPollingService(RestTemplate restTemplate) {
	this.restTemplate = restTemplate;
    }

    // 🔐 Login once on startup
    @PostConstruct
    public void init() {
	this.token = login("Umesh.Katakam@waterlabs.ai", "Protocol@123");
    }

    // ⏱️ Poll every 5 seconds
    @Scheduled(fixedDelay = 5000)
    public void pollAgentStatus() {
	try {
	    Map<String, Object> agent = getAgent();
	    String runtimeStatus = (String) agent.get("runtimeStatus");
//	    runtimeStatus = "start";
	    System.out.println(" run time status " + runtimeStatus);
	    if (AgentRuntimeStatus.START.getValue().equals(runtimeStatus) && !botRunning) {

//		List data = getData1(Bulkid);
//		System.out.println(data);
		Bot.startBot(agentId);

//		updateAgentStatus(AgentRuntimeStatus.RUNNING);
		botRunning = true;
	    }

	    if (AgentRuntimeStatus.STOP.getValue().equals(runtimeStatus)) {
		System.out.println(" stop bot");
		BotService.stopBot();
//		updateAgentStatus(AgentRuntimeStatus.IDLE);
		botRunning = false;
	    }

	} catch (Exception ex) {
	    ex.printStackTrace();
	    System.err.println("Polling failed: " + ex.getMessage());
	}
    }

    // ---------- LOGIN ----------
    private String login(String email, String password) {

	HttpHeaders headers = new HttpHeaders();
	headers.setContentType(MediaType.APPLICATION_JSON);

	Map<String, String> body = Map.of("email", email, "password", password);

	HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

	ResponseEntity<Map> response = restTemplate.postForEntity(LOGIN_URL, request, Map.class);

	Map data = (Map) response.getBody().get("data");
	return (String) data.get("token");
    }

    // ---------- GET AGENT ----------
    private Map<String, Object> getAgent() {

	HttpHeaders headers = new HttpHeaders();
	headers.setBearerAuth(token);

	HttpEntity<Void> request = new HttpEntity<>(headers);

	ResponseEntity<Map> response = restTemplate.exchange(AGENT_URL, HttpMethod.GET, request, Map.class);

	Map data = (Map) response.getBody().get("data");

	Map agent = (Map) data.get("agent");
//	Map bulk = (Map) agent.get("bulk_processing_id");
//	System.out.println(" bulk data\n" + bulk);
//	Bulkid = (String) bulk.get("_id");
	return (Map<String, Object>) data.get("agent");
    }

    // ---------- PATCH ----------
    private void updateAgentStatus(AgentRuntimeStatus status) {

	HttpHeaders headers = new HttpHeaders();
	headers.setBearerAuth(token);
	headers.setContentType(MediaType.APPLICATION_JSON);

	Map<String, String> body = Map.of("runtimeStatus", status.getValue());

	HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

	restTemplate.exchange(AGENT_URL, HttpMethod.PATCH, request, Void.class);
    }

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

    private List<Map<String, String>> getData1(String bulkid) {

	HttpHeaders headers = new HttpHeaders();
	headers.setBearerAuth(token);

	HttpEntity<Void> request = new HttpEntity<>(headers);

	String bulkUrl = "http://10.1.240.225:8001/api/bulk-data/agent/698ae5c9b0bf82d7668c29c8" + "?bulk_id=" + bulkid;
	System.out.println(bulkUrl);
	ResponseEntity<Map> response = restTemplate.exchange(bulkUrl, HttpMethod.GET, request, Map.class);
	System.out.println(" url " + bulkUrl + "?bulk_id=" + bulkid);
	System.out.println(" bulk \n" + response);
	Map data = (Map) response.getBody().get("data");
	List bulkData = (List) data.get("bulkData");
	Map bulkData0 = (Map) bulkData.get(0);
	List records = (List) bulkData0.get("records");
	System.out.println(" data is" + records);
	return records;
    }

    private static final String STAGES_API_BASE = "http://10.1.240.225:8001/api/agent-logs";

    /**
     * Updates agent log stage for a record using RestTemplate. Endpoint: PATCH
     * /api/agent-logs/{agentId}/stages
     */
    public void updateRecordStages(String agentId, int step, long recordId, String status) {
	updateRecordStages(agentId, step, String.valueOf(recordId), status);
    }

    public void updateRecordStages(String agentId, int step, String recordId, String status) {
	String url = STAGES_API_BASE + "/" + agentId + "/stages";

	HttpHeaders headers = new HttpHeaders();
	headers.setBearerAuth(token);
	headers.setContentType(MediaType.APPLICATION_JSON);

	Map<String, Object> body = Map.of("step", step, "status", status, "recordId", recordId);

	HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

	try {
	    ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PATCH, request, Map.class);
	    System.out.println("Record stage updated. HTTP status: " + response.getStatusCode());
	} catch (Exception e) {
	    System.err.println("Failed to update record stage: " + e.getMessage());
	    e.printStackTrace();
	}
    }

    public void updateRecordStages(String bulkId, String agentId, String recordId, String id, String status) {
	String url = "http://localhost:3000/api/bulk-data/update-record-status";

	HttpHeaders headers = new HttpHeaders();
	headers.setBearerAuth(token);
	headers.setContentType(MediaType.APPLICATION_JSON);

	Map<String, Object> body = Map.of("bulk_id", bulkId, "agent_id", agentId, "id", id, "status", status,
		"recordId", recordId, "error", Map.of());

	HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

	try {
	    ResponseEntity<Void> response = restTemplate.exchange(url, HttpMethod.PATCH, request, Void.class);
	    System.out.println("Record status updated. HTTP status: " + response.getStatusCode());
	} catch (Exception e) {
	    System.err.println("Failed to update record status: " + e.getMessage());
	    e.printStackTrace();
	}
    }

}
