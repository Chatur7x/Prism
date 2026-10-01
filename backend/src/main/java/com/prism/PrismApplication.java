package com.prism;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PRISM - auditable AI analysis platform.
 *
 * Architectural invariant enforced by the shape of this application:
 * DETERMINISTIC JAVA OWNS ALL STATE. THE LLM ONLY PROPOSES.
 * THE HUMAN VERIFIER IS THE FINAL AUTHORITY.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableAsync
@EnableScheduling
public class PrismApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrismApplication.class, args);
    }
}
