package com.plrs.web.quiz;

import com.plrs.application.quiz.SubmitQuizAttemptCommand;
import com.plrs.application.quiz.SubmitQuizAttemptResult;
import com.plrs.application.quiz.SubmitQuizAttemptUseCase;
import com.plrs.domain.quiz.AnswerSubmission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JWT-authenticated REST surface for quiz submission at
 * {@code POST /api/quiz-attempts}. Restricted to {@code STUDENT}: only
 * learners submit attempts.
 *
 * <p>Traces to: FR-20 (server-authoritative scoring), §3.c.6.3.
 */
@RestController
@RequestMapping("/api/quiz-attempts")
@ConditionalOnProperty(name = "spring.datasource.url")
@Tag(name = "Quiz Attempts", description = "Submit quiz attempts; server-authoritative scoring (FR-20)")
public class QuizAttemptController {

    private final SubmitQuizAttemptUseCase useCase;

    public QuizAttemptController(SubmitQuizAttemptUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(
            summary = "Submit a quiz attempt (STUDENT only)",
            description = "Server-side scoring; updates mastery via TX-01 and emits an outbox event.")
    public ResponseEntity<SubmitQuizAttemptResponse> submit(
            @Valid @RequestBody SubmitQuizAttemptRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userUuid = UUID.fromString(auth.getName());
        List<AnswerSubmission> answers =
                req.answers() == null
                        ? List.of()
                        : req.answers().stream()
                                .map(
                                        a ->
                                                new AnswerSubmission(
                                                        a.itemOrder(), a.selectedOptionOrder()))
                                .toList();
        SubmitQuizAttemptResult r =
                useCase.handle(
                        new SubmitQuizAttemptCommand(userUuid, req.quizContentId(), answers));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SubmitQuizAttemptResponse.from(r));
    }
}
