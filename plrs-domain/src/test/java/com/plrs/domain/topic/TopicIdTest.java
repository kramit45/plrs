package com.plrs.domain.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class TopicIdTest {

    @Test
    void ofBoxedLongRoundTripsThroughValue() {
        TopicId id = TopicId.of(Long.valueOf(42L));

        assertThat(id.value()).isEqualTo(42L);
    }

    @Test
    void ofPrimitiveLongRoundTripsThroughValue() {
        TopicId id = TopicId.of(1_234_567L);

        assertThat(id.value()).isEqualTo(1_234_567L);
    }

    @Test
    void ofRejectsNullBoxedLong() {
        assertThatThrownBy(() -> TopicId.of((Long) null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void ofRejectsZero() {
        assertThatThrownBy(() -> TopicId.of(0L))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void ofRejectsNegative() {
        assertThatThrownBy(() -> TopicId.of(-1L))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void ofPrimitiveRejectsNonPositive() {
        assertThatThrownBy(() -> TopicId.of(0L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> TopicId.of(Long.MIN_VALUE))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        TopicId a = TopicId.of(7L);
        TopicId b = TopicId.of(Long.valueOf(7L));
        TopicId other = TopicId.of(8L);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a TopicId");
    }

    @Test
    void toStringContainsNumericValue() {
        TopicId id = TopicId.of(99L);

        assertThat(id.toString()).contains("99").isEqualTo("TopicId(99)");
    }
}
