package com.plrs.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.infrastructure.testsupport.RedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Verifies that Spring Boot's Redis auto-configuration wires a working
 * {@link StringRedisTemplate} against a real Redis 7 instance:
 *
 * <ul>
 *   <li>a SET followed by a GET returns the same value, and
 *   <li>{@code connectionFactory.getConnection().ping()} returns {@code PONG}.
 * </ul>
 *
 * <p>Why a local {@link RedisITApp} rather than {@code PlrsApplication.class}:
 * {@code PlrsApplication} lives in {@code plrs-web}, which depends on
 * {@code plrs-infrastructure}; test-scoping it here would introduce a Maven
 * reactor cycle. Instead this IT boots a minimal Spring Boot application
 * scoped to the infra module with JDBC/JPA auto-config excluded so a
 * datasource is not required, matching the production Redis wiring declared
 * in {@code plrs-web/application.yml}.
 *
 * <p>Traces to: §3.c — Redis 7 for caching and JWT allow-list.
 */
@SpringBootTest(classes = RedisConnectivityIT.RedisITApp.class)
class RedisConnectivityIT extends RedisTestBase {

    @Autowired private StringRedisTemplate redisTemplate;

    @Test
    void setThenGetReturnsSameValueAndPingRespondsPong() {
        redisTemplate.opsForValue().set("plrs:test:key", "hello");
        assertThat(redisTemplate.opsForValue().get("plrs:test:key")).isEqualTo("hello");

        RedisConnectionFactory factory = redisTemplate.getRequiredConnectionFactory();
        try (var connection = factory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    @SpringBootApplication(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class
            })
    static class RedisITApp {}
}
