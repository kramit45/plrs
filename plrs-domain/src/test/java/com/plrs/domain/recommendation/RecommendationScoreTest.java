package com.plrs.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class RecommendationScoreTest {

    @Test
    void ofZeroAndOneRoundtripExactly() {
        assertThat(RecommendationScore.of(0.0).value()).isEqualTo(0.0);
        assertThat(RecommendationScore.of(1.0).value()).isEqualTo(1.0);
    }

    @Test
    void ofRoundsHalfUpToFourDecimals() {
        // 0.123456 rounds to 0.1235; 0.123444 rounds to 0.1234.
        assertThat(RecommendationScore.of(0.123456).value()).isCloseTo(0.1235, within(1e-12));
        assertThat(RecommendationScore.of(0.123444).value()).isCloseTo(0.1234, within(1e-12));
    }

    @Test
    void toBigDecimalMatchesNumeric6dot4() {
        assertThat(RecommendationScore.of(0.5).toBigDecimal().toPlainString()).isEqualTo("0.5000");
        assertThat(RecommendationScore.of(0.123456).toBigDecimal().toPlainString())
                .isEqualTo("0.1235");
    }

    @Test
    void rejectsNaN() {
        assertThatThrownBy(() -> RecommendationScore.of(Double.NaN))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsInfinite() {
        assertThatThrownBy(() -> RecommendationScore.of(Double.POSITIVE_INFINITY))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> RecommendationScore.of(Double.NEGATIVE_INFINITY))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsOutOfRange() {
        assertThatThrownBy(() -> RecommendationScore.of(-0.0001))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("[0.0, 1.0]");
        assertThatThrownBy(() -> RecommendationScore.of(1.0001))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void blendWithLambdaZeroReturnsThis() {
        RecommendationScore a = RecommendationScore.of(0.30);
        RecommendationScore b = RecommendationScore.of(0.90);
        assertThat(a.blendWith(b, 0.0).value()).isCloseTo(0.30, within(1e-9));
    }

    @Test
    void blendWithLambdaOneReturnsOther() {
        RecommendationScore a = RecommendationScore.of(0.30);
        RecommendationScore b = RecommendationScore.of(0.90);
        assertThat(a.blendWith(b, 1.0).value()).isCloseTo(0.90, within(1e-9));
    }

    @Test
    void blendWithLambdaHalfIsMidpoint() {
        RecommendationScore a = RecommendationScore.of(0.20);
        RecommendationScore b = RecommendationScore.of(0.80);
        assertThat(a.blendWith(b, 0.5).value()).isCloseTo(0.50, within(1e-9));
    }

    @Test
    void blendWithRejectsNullOther() {
        assertThatThrownBy(() -> RecommendationScore.of(0.5).blendWith(null, 0.5))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void blendWithRejectsLambdaOutsideUnitInterval() {
        RecommendationScore a = RecommendationScore.of(0.5);
        RecommendationScore b = RecommendationScore.of(0.5);
        assertThatThrownBy(() -> a.blendWith(b, -0.1))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> a.blendWith(b, 1.1))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> a.blendWith(b, Double.NaN))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void equalityIsValueBased() {
        assertThat(RecommendationScore.of(0.5)).isEqualTo(RecommendationScore.of(0.5));
        assertThat(RecommendationScore.of(0.5)).isNotEqualTo(RecommendationScore.of(0.6));
        assertThat(RecommendationScore.of(0.5).hashCode())
                .isEqualTo(RecommendationScore.of(0.5).hashCode());
    }
}
