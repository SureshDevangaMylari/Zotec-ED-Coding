package com.wl.zotecAgent.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON body from the agent UI Start Agent button.
 * <pre>
 * {
 *   "chartType": "Text" | "Image",
 *   "clients": [ "104CAPE FEAR VALLEY MEDICAL CE... (CFVNC1)", ... ]
 * }
 * </pre>
 */
public class AgentStartRequest {

    private String chartType;
    private List<String> clients = new ArrayList<>();

    public String getChartType() {
	return chartType;
    }

    public void setChartType(String chartType) {
	this.chartType = chartType;
    }

    public List<String> getClients() {
	return clients;
    }

    public void setClients(List<String> clients) {
	this.clients = clients != null ? clients : new ArrayList<>();
    }

    public Map<String, Object> toMap() {
	Map<String, Object> m = new LinkedHashMap<>();
	m.put("chartType", chartType);
	m.put("clients", new ArrayList<>(clients));
	return m;
    }
}
