package com.wl.zotecAgent;

import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On {@link ApplicationReadyEvent}, launches Chrome and runs {@link Flow#Start}
 * (same Playwright setup as legacy {@link Init2}).
 */
@Component
@ConditionalOnProperty(name = "flow.auto-start", havingValue = "true", matchIfMissing = true)
public class FlowStartupRunner {

    private static final Logger logger = LogManager.getLogger(FlowStartupRunner.class);

    private final BotService botService;

    @Value("${flow.agent-id:698ae5c9b0bf82d7668c29c8}")
    private String agentId;

    public FlowStartupRunner(BotService botService) {
	this.botService = botService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
	logger.info("Application ready — starting Flow (agentId={})", agentId);
	List<?> emptyData = Collections.emptyList();
	botService.startBot(emptyData, "", agentId);
    }
}
