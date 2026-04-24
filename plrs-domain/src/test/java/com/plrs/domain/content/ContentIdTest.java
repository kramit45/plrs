package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class ContentIdTest {

    @Test
    void ofBoxedLongRoundTripsThroughValue() {
        ContentId id = ContentId.of(Long.valueOf(42L));

        assertThat(id.value()).isEqualTo(42L);
    }

    @Test
    void ofPrimitiveLongRoundTripsThroughValue() {
        ContentId id = ContentId.of(1_234_567L);

        assertThat(id.value()).isEqualTo(1_234_567L);
    }

    @Test
    void ofRejectsNullBoxedLong() {
        assertThatThrownBy(() -> ContentId.of((Long) null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void ofRejectsZero() {
        assertThatThrownBy(() -> ContentId.of(0L))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void ofRejectsNegative() {
        assertThatThrownBy(() -> ContentId.of(-1L))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void ofPrimitiveRejectsNonPositive() {
        assertThatThrownBy(() -> ContentId.of(0L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> ContentId.of(Long.MIN_VALUE))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        ContentId a = ContentId.of(7L);
        ContentId b = ContentId.of(Long.valueOf(7L));
        ContentId other = ContentId.of(8L);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a ContentId");
    }

    @Test
    void toStringContainsNumericValue() {
        ContentId id = ContentId.of(99L);

        assertThat(id.toString()).contains("99").isEqualTo("ContentId(99)");
    }
}
