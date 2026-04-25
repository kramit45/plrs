package com.plrs.infrastructure.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Routes outbox events to Kafka topics based on
 * {@code OutboxEvent.aggregateType()}. Topic names are configurable
 * (so deployment overrides can run plrs.* alongside other tenants on
 * a shared cluster) but the routing rules are constants — adding a
 * new aggregate type means editing this resolver, which is the right
 * place to think about whether the new event type needs its own
 * topic or fits an existing one.
 */
@Component
@ConditionalOnProperty(name = "plrs.kafka.enabled", havingValue = "true")
public class KafkaTopicResolver {

    /** Catch-all topic for aggregate types without an explicit mapping. */
    public static final String DEFAULT_TOPIC = "plrs.events";

    private final String interactionsTopic;
    private final String masteryTopic;

    public KafkaTopicResolver(
            @Value("${plrs.kafka.topic-interactions:plrs.interactions}")
                    String interactionsTopic,
            @Value("${plrs.kafka.topic-mastery:plrs.mastery}") String masteryTopic) {
        this.interactionsTopic = interactionsTopic;
        this.masteryTopic = masteryTopic;
    }

    public String resolve(String aggregateType) {
        if (aggregateType == null) {
            return DEFAULT_TOPIC;
        }
        return switch (aggregateType) {
            case "QUIZ_ATTEMPTED", "MASTERY_UPDATED" -> masteryTopic;
            case "INTERACTION" -> interactionsTopic;
            default -> DEFAULT_TOPIC;
        };
    }
}
