package com.wl.zotecAgent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = "flow.auto-start=false")
class BotApplicationTest {

    @Autowired
    private RestTemplate restTemplate;

    @Test
    void contextLoads() {
        assertNotNull(restTemplate);
    }
}
