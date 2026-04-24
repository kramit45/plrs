package com.plrs.domain.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RatingTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void ofAcceptsEveryValueInRange(int raw) {
        Rating rating = Rating.of(raw);

        assertThat(rating.value()).isEqualTo(raw);
    }

    @Test
    void ofRejectsZero() {
        assertThatThrownBy(() -> Rating.of(0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("[1, 5]");
    }

    @Test
    void ofRejectsSix() {
        assertThatThrownBy(() -> Rating.of(6))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("[1, 5]");
    }

    @Test
    void ofRejectsNegative() {
        assertThatThrownBy(() -> Rating.of(-1))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("[1, 5]");
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -100, -1, 0, 6, 100, Integer.MAX_VALUE})
    void ofRejectsEveryValueOutsideRange(int raw) {
        assertThatThrownBy(() -> Rating.of(raw))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        Rating a = Rating.of(4);
        Rating b = Rating.of(4);
        Rating other = Rating.of(5);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a Rating");
    }

    @Test
    void toStringContainsIntegerValue() {
        Rating rating = Rating.of(3);

        assertThat(rating.toString()).contains("3").isEqualTo("Rating(3)");
    }
}
