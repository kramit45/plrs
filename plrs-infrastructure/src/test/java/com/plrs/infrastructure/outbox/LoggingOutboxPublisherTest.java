package com.plrs.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.plrs.application.outbox.OutboxEvent;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingOutboxPublisherTest {

    private LoggingOutboxPublisher publisher;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        publisher = new LoggingOutboxPublisher();
        logger = (Logger) LoggerFactory.getLogger(LoggingOutboxPublisher.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void publishLogsAtInfoWithRequiredFields() {
        OutboxEvent e =
                OutboxEvent.pending(
                        "QUIZ_ATTEMPT",
                        "agg-42",
                        "{\"score\":7}",
                        Instant.parse("2026-04-25T10:00:00Z"));

        publisher.publish(e);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent logEvent = appender.list.get(0);
        assertThat(logEvent.getLevel()).isEqualTo(Level.INFO);
        String formatted = logEvent.getFormattedMessage();
        assertThat(formatted)
                .contains("OUTBOX_PUBLISH")
                .contains("aggregate_type=QUIZ_ATTEMPT")
                .contains("aggregate_id=agg-42");
    }

    @Test
    void publishNeverThrows() {
        OutboxEvent e =
                OutboxEvent.pending("QUIZ", "agg", "{}", Instant.parse("2026-04-25T10:00:00Z"));

        assertThatCode(() -> publisher.publish(e)).doesNotThrowAnyException();
    }

    @Test
    void payloadSizeReportedCorrectly() {
        String payload = "{\"x\":\"hello-world\"}"; // 19 chars
        OutboxEvent e =
                OutboxEvent.pending(
                        "Q", "a", payload, Instant.parse("2026-04-25T10:00:00Z"));

        publisher.publish(e);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("payload_size=" + payload.length());
    }
}
