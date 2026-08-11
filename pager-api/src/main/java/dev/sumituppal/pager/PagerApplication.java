package dev.sumituppal.pager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Pager — AI Incident Response Agent.
 *
 * Entry point for the Spring Boot application. Each subsequent PR
 * adds one concern (persistence, ingress, orchestration, specialists,
 * memory) as documented in docs/architecture.html Part IV.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PagerApplication.class, args);
    }
}
