package com.plrs.domain.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class PathIdTest {

    @Test
    void ofBoxedLongRoundTripsThroughValue() {
        PathId id = PathId.of(Long.valueOf(42L));

        assertThat(id.value()).isEqualTo(42L);
    }

    @Test
    void ofPrimitiveLongRoundTripsThroughValue() {
        PathId id = PathId.of(7_654_321L);

        assertThat(id.value()).isEqualTo(7_654_321L);
    }

    @Test
    void ofRejectsNullBoxedLong() {
        assertThatThrownBy(() -> PathId.of((Long) null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void ofRejectsZero() {
        assertThatThrownBy(() -> PathId.of(0L))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void ofRejectsNegative() {
        assertThatThrownBy(() -> PathId.of(-1L))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void ofPrimitiveRejectsNonPositive() {
        assertThatThrownBy(() -> PathId.of(0L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> PathId.of(Long.MIN_VALUE))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void equalsAndHashCodeKeyByValue() {
        PathId a = PathId.of(99L);
        PathId b = PathId.of(99L);
        PathId other = PathId.of(100L);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(other);
    }

    @Test
    void toStringIncludesValue() {
        assertThat(PathId.of(42L)).hasToString("PathId(42)");
    }
}
