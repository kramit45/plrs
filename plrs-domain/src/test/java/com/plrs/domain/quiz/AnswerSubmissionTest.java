package com.plrs.domain.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class AnswerSubmissionTest {

    @Test
    void validConstruction() {
        AnswerSubmission a = new AnswerSubmission(1, 2);

        assertThat(a.itemOrder()).isEqualTo(1);
        assertThat(a.selectedOptionOrder()).isEqualTo(2);
    }

    @Test
    void rejectsItemOrderZero() {
        assertThatThrownBy(() -> new AnswerSubmission(0, 1))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("itemOrder");
    }

    @Test
    void rejectsNegativeSelectedOptionOrder() {
        assertThatThrownBy(() -> new AnswerSubmission(1, -1))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("selectedOptionOrder");
    }
}
