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
 *       With DataSource excluded, {@code FlywayAutoConfiguration} also
 *       short-circuits on its {@code @ConditionalOnBean(DataSource.class)}.
 *   <li>{@code spring.datasource.url} is overridden to {@code false} so the
 *       {@code @ConditionalOnProperty} on {@code PersistenceConfig} evaluates
 *       to absent and {@code @EnableJpaRepositories} does not fire.
 *   <li>Redis auto-config is left enabled — the Lettuce factory constructs
 *       lazily, so no connection is attempted during context load.
 * </ul>
 *
 * <p>Integration tests that exercise the real datasource live in
 * plrs-infrastructure and extend {@code PostgresTestBase}; the Redis
 * round-trip lives alongside them and extends {@code RedisTestBase}.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
        })
class PlrsApplicationTests {

    @Test
    void contextLoads() {}
}
