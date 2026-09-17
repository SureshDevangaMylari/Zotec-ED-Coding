package com.wl.zotecAgent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the agent UI from {@code com/wl/zotecAgent/staticResources} on port 8080.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
	registry.addResourceHandler("/**")
		.addResourceLocations(
			"classpath:/com/wl/zotecAgent/staticResources/",
			"file:src/main/java/com/wl/zotecAgent/staticResources/")
		.setCachePeriod(0);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
	registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
	registry.addMapping("/**").allowedOriginPatterns("*").allowedMethods("*");
    }
}
