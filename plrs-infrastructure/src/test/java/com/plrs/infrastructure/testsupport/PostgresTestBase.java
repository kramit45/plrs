package com.plrs.infrastructure.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reusable base class for integration tests that need a real PostgreSQL
 * instance. A single {@code postgres:15-alpine} container is started once
 * per JVM (static field + static initializer) and shared across all
 * subclasses; its JDBC URL and credentials are published to the Spring
 * environment via {@link DynamicPropertySource}, overriding the env-driven
 * defaults in {@code application.yml}.
 *
 * <p>Traces to: §3.e — Testcontainers-backed integration tests.
 */
@Testcontainers
public abstract class PostgresTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("plrs")
                    .withUsername("plrs")
                    .withPassword("plrs");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Spring caches one ApplicationContext per IT class; with default
        // Hikari pool (10) and ~17+ ITs in the module, the cumulative pool
        // can blow past Postgres's default max_connections=100. Cap the
        // per-context pool small — each IT only ever uses a few connections.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
    }
}
