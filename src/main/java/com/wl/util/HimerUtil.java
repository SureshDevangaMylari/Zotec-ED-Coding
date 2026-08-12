package com.wl.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;

public class HimerUtil {
    private static final String BASE_URL = "http://10.1.241.22:8000";
    private static final String USERNAME = "Megana.Srinivas@waterlabs.ai"; // Replace with your credentials
    private static final String PASSWORD = "admin@123";
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
	String externalAgentId="@VOBAvailityExtraction";
	boolean isSuccess=true;
	 
	Map<String, Object> outputData = new HashMap<>();
	String errorMessage = null;
	String errorTraceback = null;

	String authToken=authenticate();
	logExecution(authToken, externalAgentId, outputData, outputData, errorMessage, errorTraceback, 0, true);
	System.out.println(" working");
    }

    public static String authenticate() {
	try {
	    Map<String, Object> credentials = new HashMap<>();
	    credentials.put("username", USERNAME);
	    credentials.put("password", PASSWORD);
	    String json = objectMapper.writeValueAsString(credentials);
	    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/api/auth/token/"))
		    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
	    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	    if (response.statusCode() == 200) {
		JsonNode jsonResponse = objectMapper.readTree(response.body());
		return jsonResponse.get("token").asText();
	    }
	} catch (Exception e) {
	    System.err.println("Authentication error: " + e.getMessage());
	}
	return null;
    }

    public static boolean logExecution(String authToken, String externalAgentId, Map<String, Object> inputData,
	    Map<String, Object> outputData, String errorMessage, String errorTraceback, double executionTimeSeconds,
	    boolean success) {
	System.out.println("execution inside");
	try {
	    Map<String, Object> executionData = new HashMap<>();
	    executionData.put("external_agent_id", externalAgentId);
	    executionData.put("input_data", inputData != null ? inputData : new HashMap<>());
	    executionData.put("success", success);
	    executionData.put("execution_time_seconds", executionTimeSeconds);
	    if (outputData != null) {
		executionData.put("output_data", outputData);
	    }
	    if (errorMessage != null) {
		executionData.put("error_message", errorMessage);
	    }
	    if (errorTraceback != null) {
		executionData.put("error_traceback", errorTraceback);
	    }
	    String json = objectMapper.writeValueAsString(executionData);
	    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/api/bot/log/"))
		    .header("Content-Type", "application/json").header("Authorization", "Token " + authToken)
		    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
	    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	    System.out.println("response:::::" + response);
	    return response.statusCode() == 201;

	} catch (Exception e) {
	    System.err.println("Execution logging error: " + e.getMessage());
	    return false;
	}
    }

    public void yourExistingBotFunction() {
	// Step 1: Authenticate
	String authToken = authenticate();
	if (authToken == null) {
	    System.err.println("Authentication failed!");
	    return;
	}
	long startTime = System.currentTimeMillis();
	Map<String, Object> inputData = new HashMap<>();
	inputData.put("param1", "value1");
	inputData.put("param2", "value2");
	try {
	    // Your existing bot logic here
	    Map<String, Object> result = processYourData();
	    double executionTime = (System.currentTimeMillis() - startTime) / 1000.0;
	    // Log successful execution
	    logExecution(authToken, "497653fe", inputData, result, null, null, executionTime, true);
	} catch (Exception e) {
	    double executionTime = (System.currentTimeMillis() - startTime) / 1000.0;
	    // Log failed execution
	    logExecution(authToken, "497653fe", inputData, null, e.getMessage(), getStackTrace(e), executionTime,
		    false);
	}
    }

    private Map<String, Object> processYourData() {
	// Your existing bot logic here
	Map<String, Object> result = new HashMap<>();
	result.put("status", "completed");
	result.put("records_processed", 100);
	return result;
    }

    private String getStackTrace(Exception e) {
	java.io.StringWriter sw = new java.io.StringWriter();
	java.io.PrintWriter pw = new java.io.PrintWriter(sw);
	e.printStackTrace(pw);
	return sw.toString();
    }
}