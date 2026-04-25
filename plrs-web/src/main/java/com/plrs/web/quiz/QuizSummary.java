package com.plrs.web.quiz;

import com.plrs.domain.content.Content;

/** Lightweight quiz header for the attempt and result pages. */
public record QuizSummary(Long contentId, String title, int itemCount) {

    public static QuizSummary from(Content quiz) {
        return new QuizSummary(quiz.id().value(), quiz.title(), quiz.quizItems().size());
    }
}
