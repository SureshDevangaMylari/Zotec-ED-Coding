package com.wl.zotecAgent.himer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = { "com.wl" })
@EnableScheduling
public class BotApplication {
    public static void main(String[] args) {
	// Allow PopUp snackbars (Swing) - Spring Boot defaults to headless for web apps
	System.setProperty("java.awt.headless", "false");
	SpringApplication.run(BotApplication.class, args);
    }
}