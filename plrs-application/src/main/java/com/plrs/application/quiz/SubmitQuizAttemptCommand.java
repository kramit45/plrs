package com.plrs.application.quiz;

import com.plrs.domain.quiz.AnswerSubmission;
import java.util.List;
import java.util.UUID;

/**
 * Command for {@link SubmitQuizAttemptUseCase}: submit a learner's
 * answers to a quiz. {@code attemptedAt} is server-stamped from the
 * use case's clock so clients can't backdate.
 */
public record SubmitQuizAttemptCommand(
        UUID userUuid, Long quizContentId, List<AnswerSubmission> answers) {}
