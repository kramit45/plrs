package com.plrs.infrastructure.topic;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicDraft;
import com.plrs.domain.topic.TopicId;
import com.plrs.infrastructure.user.AuditFieldsEmbeddable;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TopicMapperTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-04-25T11:00:00Z");

    private final TopicMapper mapper = new TopicMapper();

    @Test
    void toDomainRoundTripsRootTopic() {
        TopicJpaEntity entity =
                new TopicJpaEntity(
                        42L,
                        "Algebra",
                        "Intro to algebra",
                        null,
                        new AuditFieldsEmbeddable(T0, T1, "system"));

        Topic topic = mapper.toDomain(entity);

        assertThat(topic.id()).isEqualTo(TopicId.of(42L));
        assertThat(topic.name()).isEqualTo("Algebra");
        assertThat(topic.description()).contains("Intro to algebra");
        assertThat(topic.parentTopicId()).isEmpty();
        assertThat(topic.audit().createdBy()).isEqualTo("system");
        assertThat(topic.audit().createdAt()).isEqualTo(T0);
        assertThat(topic.audit().updatedAt()).isEqualTo(T1);
    }

    @Test
    void toDomainRoundTripsChildTopic() {
        TopicJpaEntity entity =
                new TopicJpaEntity(
                        99L,
                        "Linear Equations",
                        null,
                        7L,
                        new AuditFieldsEmbeddable(T0, T0, "system"));

        Topic topic = mapper.toDomain(entity);

        assertThat(topic.id()).isEqualTo(TopicId.of(99L));
        assertThat(topic.parentTopicId()).contains(TopicId.of(7L));
        assertThat(topic.description()).isEmpty();
    }

    @Test
    void toEntityFromTopicPreservesAllFields() {
        Topic topic =
                Topic.rehydrate(
                        TopicId.of(42L),
                        "Algebra",
                        "Intro",
                        Optional.of(TopicId.of(1L)),
                        AuditFields.initial("system", T0).touchedAt(T1));

        TopicJpaEntity entity = mapper.toEntity(topic);

        assertThat(entity.getId()).isEqualTo(42L);
        assertThat(entity.getTopicName()).isEqualTo("Algebra");
        assertThat(entity.getDescription()).isEqualTo("Intro");
        assertThat(entity.getParentTopicId()).isEqualTo(1L);
        assertThat(entity.getAudit().getCreatedBy()).isEqualTo("system");
    }

    @Test
    void toEntityFromTopicMapsEmptyParentToNullColumn() {
        Topic topic =
                Topic.rehydrate(
                        TopicId.of(42L),
                        "Algebra",
                        null,
                        Optional.empty(),
                        AuditFields.initial("system", T0));

        TopicJpaEntity entity = mapper.toEntity(topic);

        assertThat(entity.getParentTopicId()).isNull();
        assertThat(entity.getDescription()).isNull();
    }

    @Test
    void toEntityFromDraftLeavesIdNullForGeneration() {
        TopicDraft draft =
                new TopicDraft(
                        "Algebra",
                        "Intro",
                        Optional.of(TopicId.of(5L)),
                        AuditFields.initial("system", T0));

        TopicJpaEntity entity = mapper.toEntity(draft);

        assertThat(entity.getId()).as("id must be null so IDENTITY generates it").isNull();
        assertThat(entity.getTopicName()).isEqualTo("Algebra");
        assertThat(entity.getParentTopicId()).isEqualTo(5L);
    }

    @Test
    void toEntityFromDraftMapsEmptyParentToNull() {
        TopicDraft draft =
                new TopicDraft(
                        "Algebra",
                        null,
                        Optional.empty(),
                        AuditFields.initial("system", T0));

        TopicJpaEntity entity = mapper.toEntity(draft);

        assertThat(entity.getId()).isNull();
        assertThat(entity.getParentTopicId()).isNull();
        assertThat(entity.getDescription()).isNull();
    }

    @Test
    void nullArgumentsPassThroughAsNull() {
        assertThat(mapper.toDomain(null)).isNull();
        assertThat(mapper.toEntity((Topic) null)).isNull();
        assertThat(mapper.toEntity((TopicDraft) null)).isNull();
        assertThat(mapper.toTopicId(null)).isNull();
        assertThat(mapper.toOptionalTopicId(null)).isEmpty();
        assertThat(mapper.toAuditFields(null)).isNull();
        assertThat(mapper.toAuditFieldsEmbeddable(null)).isNull();
    }
}
