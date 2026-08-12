package com.wl.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Polls the backend for agent control status (START / STOP).
 * Backend controls the agent; this client reacts in real time.
 */
public class BackendAgentControlClient {

    public static final String BASE_URL = "http://10.1.241.22:8000";
    private static final String USERNAME = "i"; // Replace with your credentials if different
    private static final String PASSWORD = "admin123";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public enum AgentStatus {
        START,
        STOP,
        UNKNOWN
    }

    /**
     * Fetches current agent control status from the backend.
     * Expects JSON response: { "status": "START" } or { "status": "STOP" }
     */
    public static AgentStatus getAgentStatus() {
        try {
            String auth = USERNAME + ":" + PASSWORD;
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/agent/status/"))
                    .header("Authorization", "Basic " + encoded)
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return AgentStatus.UNKNOWN;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode statusNode = root.path("status");
            if (statusNode.isMissingNode()) {
                return AgentStatus.UNKNOWN;
            }
            String value = statusNode.asText().trim().toUpperCase();
            if ("START".equals(value)) {
                return AgentStatus.START;
            }
            if ("STOP".equals(value)) {
                return AgentStatus.STOP;
            }
            return AgentStatus.UNKNOWN;
        } catch (Exception e) {
            return AgentStatus.UNKNOWN;
        }
    }
}
