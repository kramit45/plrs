package com.plrs.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class RecommendationReasonTest {

    @Test
    void acceptsShortText() {
        assertThat(new RecommendationReason("Because you mastered Algebra").text())
                .isEqualTo("Because you mastered Algebra");
    }

    @Test
    void trimsWhitespace() {
        assertThat(new RecommendationReason("   trimmed   ").text()).isEqualTo("trimmed");
    }

    @Test
    void acceptsExactlyMaxLength() {
        String exactlyMax = "a".repeat(RecommendationReason.MAX_LENGTH);
        assertThat(new RecommendationReason(exactlyMax).text()).isEqualTo(exactlyMax);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new RecommendationReason(null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> new RecommendationReason(""))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new RecommendationReason("   "))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new RecommendationReason("\t\n"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsOverlong() {
        String overlong = "a".repeat(RecommendationReason.MAX_LENGTH + 1);
        assertThatThrownBy(() -> new RecommendationReason(overlong))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("at most 200");
    }

    @Test
    void equalityIsRecordValueBased() {
        assertThat(new RecommendationReason("hello"))
                .isEqualTo(new RecommendationReason("hello"));
        assertThat(new RecommendationReason("hello"))
                .isNotEqualTo(new RecommendationReason("world"));
    }
}
