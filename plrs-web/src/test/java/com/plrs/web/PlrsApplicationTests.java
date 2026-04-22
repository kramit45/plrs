package com.plrs.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test that the Spring context boots. The plrs-web module itself has
 * no Testcontainers setup, so this test strips every database-aware bean
 * out of the context:
 *
 * <ul>
 *   <li>DataSource + Hibernate auto-config are excluded (Hibernate otherwise
 *       tries to read JDBC metadata at startup to determine the dialect).
 *   <li>{@code spring.datasource.url} is overridden to {@code false} so the
 *       {@code @ConditionalOnProperty} on {@code PersistenceConfig} evaluates
 *       to absent and {@code @EnableJpaRepositories} does not fire.
 *   <li>Flyway and Redis auto-config remain excluded via {@code application.yml}
 *       until steps 13–14 wire them up.
 * </ul>
 *
 * <p>Integration tests that exercise the real datasource live in
 * plrs-infrastructure and extend {@code PostgresTestBase}.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
class PlrsApplicationTests {

    @Test
    void contextLoads() {}
}
