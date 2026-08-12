package com.wl.zotecAgent.himer;

public enum AgentRuntimeStatus {
    START("start"),
    STOP("stop"),
    RUNNING("running"),
    IDLE("idle");

    private final String value;

    AgentRuntimeStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}

