package com.plrs.web.quiz;

import com.plrs.domain.quiz.QuizItemOption;

/**
 * Sanitised view of one option for the attempt page.
 * <strong>Crucially</strong>, this record does NOT carry the
 * {@code isCorrect} flag — exposing it would let a student inspect
 * the page source and find the answer.
 */
public record QuizOptionView(int optionOrder, String text) {

    public static QuizOptionView from(QuizItemOption opt) {
        return new QuizOptionView(opt.optionOrder(), opt.optionText());
    }
}
