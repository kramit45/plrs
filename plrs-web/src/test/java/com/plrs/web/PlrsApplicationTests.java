package com.plrs.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test that the Spring context boots. Uses the application's own
 * temporary auto-configure exclusions (DataSource / Hibernate / Flyway /
 * Redis) defined in {@code application.yml}; later iterations configure real
 * infrastructure via Testcontainers.
 */
@SpringBootTest
class PlrsApplicationTests {

    @Test
    void contextLoads() {}
}
