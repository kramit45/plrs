package com.plrs.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KafkaTopicResolverTest {

    private final KafkaTopicResolver resolver =
            new KafkaTopicResolver("plrs.interactions", "plrs.mastery");

    @Test
    void interactionRoutesToInteractionsTopic() {
        assertThat(resolver.resolve("INTERACTION")).isEqualTo("plrs.interactions");
    }

    @Test
    void quizAttemptedRoutesToMasteryTopic() {
        assertThat(resolver.resolve("QUIZ_ATTEMPTED")).isEqualTo("plrs.mastery");
    }

    @Test
    void masteryUpdatedRoutesToMasteryTopic() {
        assertThat(resolver.resolve("MASTERY_UPDATED")).isEqualTo("plrs.mastery");
    }

    @Test
    void unknownAggregateRoutesToDefaultTopic() {
        assertThat(resolver.resolve("SOMETHING_NEW"))
                .isEqualTo(KafkaTopicResolver.DEFAULT_TOPIC);
    }

    @Test
    void nullAggregateRoutesToDefaultTopic() {
        assertThat(resolver.resolve(null)).isEqualTo(KafkaTopicResolver.DEFAULT_TOPIC);
    }
}
