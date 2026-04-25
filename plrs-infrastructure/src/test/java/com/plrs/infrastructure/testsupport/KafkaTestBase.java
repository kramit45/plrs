package com.plrs.infrastructure.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Reusable base for ITs that need Postgres + Kafka. Extends
 * {@link PostgresTestBase} so subclasses get both containers from a
 * single inheritance hop (Java's single-inheritance constraint
 * forces this layout).
 *
 * <p>One {@code confluentinc/cp-kafka} container per JVM, broadcast
 * to the Spring environment via:
 *
 * <ul>
 *   <li>{@code spring.kafka.bootstrap-servers} (binds the
 *       Kafka producer template)
 *   <li>{@code spring.kafka.producer.bootstrap-servers} (override
 *       in case application.yml fixed it earlier)
 *   <li>{@code plrs.kafka.bootstrap-servers} (the meta-property
 *       application.yml resolves)
 *   <li>{@code plrs.kafka.enabled=true} (activates
 *       {@code KafkaOutboxPublisher})
 * </ul>
 *
 * <p>Traces to: §3.e — Testcontainers-backed integration tests.
 */
@Testcontainers
public abstract class KafkaTestBase extends PostgresTestBase {

    protected static final KafkaContainer KAFKA =
            new KafkaContainer(
                    DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add(
                "spring.kafka.producer.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("plrs.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("plrs.kafka.enabled", () -> "true");
    }
}
