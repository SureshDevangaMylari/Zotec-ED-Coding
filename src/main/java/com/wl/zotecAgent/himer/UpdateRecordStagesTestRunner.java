package com.wl.zotecAgent.himer;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
@ConditionalOnProperty(name = "app.test.update-record-stages", havingValue = "true")
public class UpdateRecordStagesTestRunner implements CommandLineRunner {

    private final AgentPollingService agentPollingService;

    public UpdateRecordStagesTestRunner(AgentPollingService agentPollingService) {
	this.agentPollingService = agentPollingService;
    }

    @Override
    public void run(String... args) throws Exception {
	System.out.println(">>> UpdateRecordStagesTestRunner: Testing updateRecordStages...");
	String agentId = "69ae9c1838dc818f286b2957";
	long recordId = 123L;
	agentPollingService.updateRecordStages(agentId, 1, recordId, "COMPLETED");
	System.out.println(">>> UpdateRecordStagesTestRunner: Done.");
    }
}
