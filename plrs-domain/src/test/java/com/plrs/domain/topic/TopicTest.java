package com.plrs.domain.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TopicTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-04-24T10:15:30Z"), ZoneOffset.UTC);

    private static AuditFields audit() {
        return AuditFields.initial("test", FIXED_CLOCK);
    }

    private static Topic root() {
        return Topic.rehydrate(TopicId.of(1L), "Algebra", "Intro to algebra", Optional.empty(), audit());
    }

    @Test
    void rehydrateRoundTripsAllFields() {
        TopicId id = TopicId.of(42L);
        TopicId parent = TopicId.of(7L);
        AuditFields a = audit();

        Topic topic = Topic.rehydrate(id, "Linear Equations", "y = mx + c", Optional.of(parent), a);

        assertThat(topic.id()).isEqualTo(id);
        assertThat(topic.name()).isEqualTo("Linear Equations");
        assertThat(topic.description()).contains("y = mx + c");
        assertThat(topic.parentTopicId()).contains(parent);
        assertThat(topic.audit()).isEqualTo(a);
    }

    @Test
    void rehydrateAllowsNullDescriptionReturningEmptyOptional() {
        Topic topic = Topic.rehydrate(TopicId.of(1L), "Algebra", null, Optional.empty(), audit());

        assertThat(topic.description()).isEmpty();
    }

    @Test
    void rehydrateTrimsName() {
        Topic topic = Topic.rehydrate(
                TopicId.of(1L), "  Algebra  ", null, Optional.empty(), audit());

        assertThat(topic.name()).isEqualTo("Algebra");
    }

    @Test
    void rehydrateRejectsNullId() {
        assertThatThrownBy(
                        () -> Topic.rehydrate(null, "Algebra", null, Optional.empty(), audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("id");
    }

    @Test
    void rehydrateRejectsNullName() {
        assertThatThrownBy(
                        () -> Topic.rehydrate(
                                TopicId.of(1L), null, null, Optional.empty(), audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rehydrateRejectsBlankName() {
        assertThatThrownBy(
                        () -> Topic.rehydrate(
                                TopicId.of(1L), "   ", null, Optional.empty(), audit()))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rehydrateRejectsNameOver120Chars() {
        String tooLong = "x".repeat(121);

        assertThatThrownBy(
                        () -> Topic.rehydrate(
                                TopicId.of(1L), tooLong, null, Optional.empty(), audit()))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("120");
    }

    @Test
    void rehydrateAcceptsNameExactly120Chars() {
        String exactly120 = "x".repeat(120);

        Topic topic = Topic.rehydrate(
                TopicId.of(1L), exactly120, null, Optional.empty(), audit());

        assertThat(topic.name()).hasSize(120);
    }

    @Test
    void rehydrateRejectsDescriptionOver500Chars() {
        String tooLong = "x".repeat(501);

        assertThatThrownBy(
                        () -> Topic.rehydrate(
                                TopicId.of(1L), "Algebra", tooLong, Optional.empty(), audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("500");
    }

    @Test
    void rehydrateRejectsNullParentTopicId() {
        assertThatThrownBy(
                        () -> Topic.rehydrate(TopicId.of(1L), "Algebra", null, null, audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("parentTopicId");
    }

    @Test
    void rehydrateRejectsNullAudit() {
        assertThatThrownBy(
                        () -> Topic.rehydrate(
                                TopicId.of(1L), "Algebra", null, Optional.empty(), null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("audit");
    }

    @Test
    void equalsAndHashCodeAreIdBased() {
        TopicId id = TopicId.of(42L);
        Topic a = Topic.rehydrate(id, "Algebra", null, Optional.empty(), audit());
        Topic b = Topic.rehydrate(id, "TOTALLY DIFFERENT NAME", "desc", Optional.of(TopicId.of(7L)), audit());
        Topic different = Topic.rehydrate(TopicId.of(99L), "Algebra", null, Optional.empty(), audit());

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a Topic");
    }

    @Test
    void toStringContainsIdAndName() {
        Topic topic = root();

        String str = topic.toString();

        assertThat(str).contains("1").contains("Algebra").contains("parentTopicId");
    }
}
