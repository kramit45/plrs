package com.plrs.domain.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class MasteryScoreTest {

    private static final double EPS = 1e-9;

    @Test
    void ofAcceptsBoundaryAndInteriorValues() {
        assertThat(MasteryScore.of(0.0).value()).isEqualTo(0.0);
        assertThat(MasteryScore.of(0.5).value()).isEqualTo(0.5);
        assertThat(MasteryScore.of(1.0).value()).isEqualTo(1.0);
    }

    @Test
    void ofRejectsJustBelowZero() {
        assertThatThrownBy(() -> MasteryScore.of(-0.001))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("[0.0, 1.0]");
    }

    @Test
    void ofRejectsJustAboveOne() {
        assertThatThrownBy(() -> MasteryScore.of(1.001))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("[0.0, 1.0]");
    }

    @Test
    void ofRejectsNaNAndInfinities() {
        assertThatThrownBy(() -> MasteryScore.of(Double.NaN))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("NaN");
        assertThatThrownBy(() -> MasteryScore.of(Double.POSITIVE_INFINITY))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> MasteryScore.of(Double.NEGATIVE_INFINITY))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void zeroAndNeutralConstantsHaveExpectedValues() {
        assertThat(MasteryScore.ZERO.value()).isEqualTo(0.0);
        assertThat(MasteryScore.NEUTRAL.value()).isEqualTo(0.5);
    }

    @Test
    void blendWithMatchesFirstGoldenValue() {
        MasteryScore current = MasteryScore.of(0.62);
        MasteryScore other = MasteryScore.of(0.80);

        MasteryScore result = current.blendWith(other, 0.24);

        assertThat(result.value()).isCloseTo(0.6632, within(EPS));
    }

    @Test
    void blendWithMatchesSecondGoldenValue() {
        MasteryScore current = MasteryScore.of(0.50);
        MasteryScore other = MasteryScore.of(0.80);

        MasteryScore result = current.blendWith(other, 0.16);

        assertThat(result.value()).isCloseTo(0.548, within(EPS));
    }

    @Test
    void blendWithAlphaZeroLeavesThisUnchanged() {
        MasteryScore current = MasteryScore.of(0.42);
        MasteryScore other = MasteryScore.of(0.99);

        MasteryScore result = current.blendWith(other, 0.0);

        assertThat(result.value()).isCloseTo(0.42, within(EPS));
    }

    @Test
    void blendWithAlphaOneReplacesWithOther() {
        MasteryScore current = MasteryScore.of(0.42);
        MasteryScore other = MasteryScore.of(0.99);

        MasteryScore result = current.blendWith(other, 1.0);

        assertThat(result.value()).isCloseTo(0.99, within(EPS));
    }

    @Test
    void blendWithRejectsAlphaOutOfRange() {
        MasteryScore current = MasteryScore.NEUTRAL;
        MasteryScore other = MasteryScore.NEUTRAL;

        assertThatThrownBy(() -> current.blendWith(other, -0.01))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("alpha");
        assertThatThrownBy(() -> current.blendWith(other, 1.01))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("alpha");
        assertThatThrownBy(() -> current.blendWith(other, Double.NaN))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("alpha");
    }

    @Test
    void blendWithRejectsNullOther() {
        MasteryScore current = MasteryScore.NEUTRAL;

        assertThatThrownBy(() -> current.blendWith(null, 0.5))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void blendWithReturnsNewInstance() {
        MasteryScore current = MasteryScore.of(0.5);
        MasteryScore other = MasteryScore.of(0.8);

        MasteryScore result = current.blendWith(other, 0.5);

        assertThat(result).isNotSameAs(current).isNotSameAs(other);
        assertThat(current.value()).isEqualTo(0.5);
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        MasteryScore a = MasteryScore.of(0.7);
        MasteryScore b = MasteryScore.of(0.7);
        MasteryScore other = MasteryScore.of(0.8);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a MasteryScore");
    }

    @Test
    void toStringFormatsToThreeDecimals() {
        assertThat(MasteryScore.of(0.6632).toString()).isEqualTo("MasteryScore(0.663)");
        assertThat(MasteryScore.ZERO.toString()).isEqualTo("MasteryScore(0.000)");
        assertThat(MasteryScore.of(1.0).toString()).isEqualTo("MasteryScore(1.000)");
    }
}
