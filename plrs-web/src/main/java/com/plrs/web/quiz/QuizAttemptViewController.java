package com.plrs.web.quiz;

import com.plrs.application.content.ContentNotFoundException;
import com.plrs.application.quiz.SubmitQuizAttemptCommand;
import com.plrs.application.quiz.SubmitQuizAttemptResult;
import com.plrs.application.quiz.SubmitQuizAttemptUseCase;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Server-rendered quiz attempt + result flow. Mounted under
 * {@code /quiz/{contentId}/attempt} so it falls under the web
 * (session-cookie) chain rather than the JWT-only API chain.
 *
 * <p>Security note: the attempt page strips {@code is_correct} from
 * options via {@link QuizItemView}/{@link QuizOptionView} so the
 * student can never inspect HTML to find the answer.
 *
 * <p>Traces to: FR-20.
 */
@Controller
@ConditionalOnProperty(name = "spring.datasource.url")
public class QuizAttemptViewController {

    private final ContentRepository contentRepository;
    private final SubmitQuizAttemptUseCase useCase;

    public QuizAttemptViewController(
            ContentRepository contentRepository, SubmitQuizAttemptUseCase useCase) {
        this.contentRepository = contentRepository;
        this.useCase = useCase;
    }

    @GetMapping("/quiz/{contentId}/attempt")
    @PreAuthorize("hasRole('STUDENT')")
    public String startAttempt(@PathVariable Long contentId, Model model) {
        Content quiz = loadQuiz(contentId);

        List<QuizItemView> items =
                quiz.quizItems().stream().map(QuizItemView::from).toList();
        model.addAttribute("quiz", QuizSummary.from(quiz));
        model.addAttribute("items", items);
        model.addAttribute("form", new QuizFormSubmission());
        return "quiz/attempt";
    }

    @PostMapping("/quiz/{contentId}/attempt")
    @PreAuthorize("hasRole('STUDENT')")
    public String submit(
            @PathVariable Long contentId,
            @ModelAttribute("form") QuizFormSubmission form,
            Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userUuid = UUID.fromString(auth.getName());

        SubmitQuizAttemptResult r =
                useCase.handle(
                        new SubmitQuizAttemptCommand(
                                userUuid, contentId, form.toAnswerSubmissions()));

        Content quiz = loadQuiz(contentId);
        model.addAttribute("quiz", QuizSummary.from(quiz));
        model.addAttribute("result", r);
        model.addAttribute(
                "resolvedFeedback",
                ResolvedFeedback.from(r.perItemFeedback(), quiz.quizItems()));
        return "quiz/result";
    }

    private Content loadQuiz(Long contentId) {
        ContentId cid = ContentId.of(contentId);
        Content quiz =
                contentRepository
                        .findById(cid)
                        .orElseThrow(() -> new ContentNotFoundException(cid));
        if (quiz.ctype() != ContentType.QUIZ) {
            throw new ContentNotFoundException(cid);
        }
        return quiz;
    }
}
