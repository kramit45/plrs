package com.plrs.infrastructure.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reusable base class for integration tests that need a real Redis
 * instance. A single {@code redis:7-alpine} container is started once per
 * JVM (static field + static initializer) and shared across all
 * subclasses; its host and mapped port are published to the Spring
 * environment via {@link DynamicPropertySource}, overriding the env-driven
 * defaults in {@code application.yml}.
 *
 * <p>Traces to: §3.c — Redis 7 for caching and JWT allow-list.
 */
@Testcontainers
public abstract class RedisTestBase {

    private static final int REDIS_PORT = 6379;

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }
}
