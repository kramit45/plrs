package com.plrs.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Personalized Learning Recommendation System (PLRS).
 *
 * <p>Scans the whole {@code com.plrs} package tree so application services,
 * infrastructure adapters, and web controllers are all discovered by the
 * single Boot context hosted in this module. JPA repository scanning and
 * {@code @EntityScan} are attached via {@link com.plrs.web.config.PersistenceConfig},
 * which activates only when a {@code spring.datasource.url} is configured —
 * that keeps the entrypoint runnable in the early iterations before the
 * database is wired up (steps 12–14).
 *
 * <p>Traces to: §3.a — web module owns the Boot entrypoint.
 */
@SpringBootApplication(scanBasePackages = "com.plrs")
public class PlrsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlrsApplication.class, args);
    }
}
