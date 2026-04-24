package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class DifficultyTest {

    @Test
    void valuesAreInDeclaredOrder() {
        assertThat(Difficulty.values())
                .containsExactly(
                        Difficulty.BEGINNER, Difficulty.INTERMEDIATE, Difficulty.ADVANCED);
    }

    @Test
    void rankMatchesOrdinalPlusOne() {
        assertThat(Difficulty.BEGINNER.rank()).isEqualTo(1);
        assertThat(Difficulty.INTERMEDIATE.rank()).isEqualTo(2);
        assertThat(Difficulty.ADVANCED.rank()).isEqualTo(3);
    }

    @Test
    void rankOrderingIsStrictlyIncreasing() {
        assertThat(Difficulty.BEGINNER.rank()).isLessThan(Difficulty.INTERMEDIATE.rank());
        assertThat(Difficulty.INTERMEDIATE.rank()).isLessThan(Difficulty.ADVANCED.rank());
    }

    @Test
    void fromNameReturnsMatchingValue() {
        assertThat(Difficulty.fromName("BEGINNER")).isEqualTo(Difficulty.BEGINNER);
        assertThat(Difficulty.fromName("INTERMEDIATE")).isEqualTo(Difficulty.INTERMEDIATE);
        assertThat(Difficulty.fromName("ADVANCED")).isEqualTo(Difficulty.ADVANCED);
    }

    @Test
    void fromNameIsCaseSensitive() {
        assertThatThrownBy(() -> Difficulty.fromName("beginner"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("beginner")
                .hasMessageContaining("BEGINNER");
    }

    @Test
    void fromNameRejectsNull() {
        assertThatThrownBy(() -> Difficulty.fromName(null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void fromNameRejectsUnknown() {
        assertThatThrownBy(() -> Difficulty.fromName("MASTER"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("MASTER");
    }
}
