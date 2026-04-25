package com.plrs.domain.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class QuizItemOptionTest {

    @Test
    void validConstructionWithIsCorrectTrue() {
        QuizItemOption o = new QuizItemOption(1, "Paris", true);

        assertThat(o.optionOrder()).isEqualTo(1);
        assertThat(o.optionText()).isEqualTo("Paris");
        assertThat(o.isCorrect()).isTrue();
    }

    @Test
    void validConstructionWithIsCorrectFalse() {
        QuizItemOption o = new QuizItemOption(2, "London", false);

        assertThat(o.isCorrect()).isFalse();
    }

    @Test
    void rejectsOptionOrderZero() {
        assertThatThrownBy(() -> new QuizItemOption(0, "x", true))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining(">= 1");
    }

    @Test
    void rejectsNegativeOptionOrder() {
        assertThatThrownBy(() -> new QuizItemOption(-1, "x", true))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining(">= 1");
    }

    @Test
    void rejectsNullOptionText() {
        assertThatThrownBy(() -> new QuizItemOption(1, null, true))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void rejectsBlankOptionText() {
        assertThatThrownBy(() -> new QuizItemOption(1, "   ", true))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> new QuizItemOption(1, "", true))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void acceptsVeryLongOptionText() {
        String longText = "x".repeat(10_000);

        QuizItemOption o = new QuizItemOption(1, longText, false);

        assertThat(o.optionText()).hasSize(10_000);
    }

    @Test
    void equalsAndHashCodeContract() {
        QuizItemOption a = new QuizItemOption(1, "Paris", true);
        QuizItemOption b = new QuizItemOption(1, "Paris", true);
        QuizItemOption different = new QuizItemOption(2, "Paris", true);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
    }
}
