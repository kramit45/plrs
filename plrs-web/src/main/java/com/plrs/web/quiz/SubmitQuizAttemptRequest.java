package com.plrs.web.quiz;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Request payload for {@code POST /api/quiz-attempts}. Empty
 * {@code answers} list is permitted (the use case treats every item
 * as unanswered → 0/N score).
 */
public record SubmitQuizAttemptRequest(@NotNull Long quizContentId, List<AnswerDto> answers) {}
