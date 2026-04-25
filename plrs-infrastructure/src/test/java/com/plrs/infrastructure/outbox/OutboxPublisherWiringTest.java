package com.plrs.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.application.outbox.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Wiring-level smoke check on the property gates — exactly one
 * {@link OutboxPublisher} is active per setting of
 * {@code plrs.kafka.enabled}.
 */
class OutboxPublisherWiringTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                    .withUserConfiguration(OutboxOnlyConfig.class);

    @Test
    void unsetDefaultsToLoggingPublisher() {
        runner.run(
                ctx -> {
                    assertThat(ctx).hasSingleBean(OutboxPublisher.class);
                    assertThat(ctx.getBean(OutboxPublisher.class))
                            .isInstanceOf(LoggingOutboxPublisher.class);
                });
    }

    @Test
    void falseDefaultsToLoggingPublisher() {
        runner.withPropertyValues("plrs.kafka.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(OutboxPublisher.class);
                            assertThat(ctx.getBean(OutboxPublisher.class))
                                    .isInstanceOf(LoggingOutboxPublisher.class);
                        });
    }

    @Test
    void trueActivatesKafkaPublisher() {
        runner.withPropertyValues(
                        "plrs.kafka.enabled=true",
                        "spring.kafka.producer.bootstrap-servers=localhost:9092")
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(OutboxPublisher.class);
                            assertThat(ctx.getBean(OutboxPublisher.class))
                                    .isInstanceOf(KafkaOutboxPublisher.class);
                        });
    }

    /**
     * Scans {@code com.plrs.infrastructure.outbox} only, so the runner
     * doesn't try to wire JPA/Redis/everything else in plrs-infrastructure.
     */
    @Configuration
    @ComponentScan(
            basePackages = "com.plrs.infrastructure.outbox",
            useDefaultFilters = false,
            includeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.REGEX,
                            pattern = "com\\.plrs\\.infrastructure\\.outbox\\."
                                    + "(LoggingOutboxPublisher|KafkaOutboxPublisher|"
                                    + "KafkaTopicResolver)"))
    static class OutboxOnlyConfig {}
}
