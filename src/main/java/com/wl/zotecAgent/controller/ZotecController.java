package com.wl.zotecAgent.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.wl.zotecAgent.BotService;

/**
 * HTTP control for Start Agent / Stop Agent from the static UI.
 */
@RestController
public class ZotecController {

    private static final Logger log = LoggerFactory.getLogger(ZotecController.class);

    private final BotService botService;

    public ZotecController(BotService botService) {
	this.botService = botService;
    }

    @PostMapping("/startAgent")
    public ResponseEntity<Map<String, Object>> startAgent(@RequestBody AgentStartRequest request) {
	Map<String, Object> body = new LinkedHashMap<>();
	if (request == null) {
	    body.put("ok", false);
	    body.put("error", "Request body required");
	    return ResponseEntity.badRequest().body(body);
	}

	String chartType = request.getChartType() != null ? request.getChartType().trim() : "";
	List<String> clients = request.getClients();

	if (chartType.isEmpty()
		|| (!"Text".equalsIgnoreCase(chartType) && !"Image".equalsIgnoreCase(chartType))) {
	    body.put("ok", false);
	    body.put("error", "chartType must be Text or Image");
	    return ResponseEntity.badRequest().body(body);
	}
	if (clients == null || clients.isEmpty()) {
	    body.put("ok", false);
	    body.put("error", "Select at least one client");
	    return ResponseEntity.badRequest().body(body);
	}
	if (botService.isRunning()) {
	    body.put("ok", false);
	    body.put("error", "Bot already running");
	    body.put("running", true);
	    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
	}

	log.info("Start Agent request: {}", request.toMap());
	boolean started = botService.startBotFromUi(chartType, clients);
	body.put("ok", started);
	body.put("running", botService.isRunning());
	body.put("chartType", chartType);
	body.put("clients", clients);
	body.put("flow", "Image".equalsIgnoreCase(chartType) ? "Flow" : "FlowText");
	if (!started) {
	    body.put("error", "Could not start bot");
	    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
	}
	return ResponseEntity.ok(body);
    }

    @PostMapping("/stopAgent")
    public ResponseEntity<Map<String, Object>> stopAgent() {
	log.info("Stop Agent requested");
	botService.stopBot();
	Map<String, Object> body = new LinkedHashMap<>();
	body.put("ok", true);
	body.put("running", botService.isRunning());
	body.put("message", "Bot stopped; clear UI inputs on client");
	return ResponseEntity.ok(body);
    }

    @GetMapping("/agentStatus")
    public Map<String, Object> agentStatus() {
	Map<String, Object> body = new LinkedHashMap<>();
	body.put("running", botService.isRunning());
	body.put("chartType", botService.getActiveChartType());
	body.put("clients", botService.getActiveClients());
	return body;
    }
}
