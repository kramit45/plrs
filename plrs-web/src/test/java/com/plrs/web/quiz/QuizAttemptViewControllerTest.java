package com.plrs.web.quiz;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.plrs.application.quiz.SubmitQuizAttemptResult;
import com.plrs.application.quiz.SubmitQuizAttemptUseCase;
import com.plrs.application.security.TokenService;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.quiz.PerItemFeedback;
import com.plrs.domain.quiz.QuizItem;
import com.plrs.domain.quiz.QuizItemOption;
import com.plrs.domain.topic.TopicId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = QuizAttemptViewController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = com.plrs.web.common.GlobalExceptionHandler.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(QuizAttemptViewControllerTest.MethodSecurityTestConfig.class)
class QuizAttemptViewControllerTest {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);
    private static final TopicId TOPIC = TopicId.of(1L);

    @Autowired private MockMvc mockMvc;

    @MockBean private ContentRepository contentRepository;
    @MockBean private SubmitQuizAttemptUseCase useCase;
    @MockBean private TokenService tokenService;

    /** Quiz with 2 items; correct option is option 1 (text "RIGHT-MARKER"). */
    private static Content sampleQuiz() {
        QuizItem item1 =
                QuizItem.of(
                        1,
                        TOPIC,
                        "Capital of France?",
                        Optional.empty(),
                        List.of(
                                new QuizItemOption(1, "Paris", true),
                                new QuizItemOption(2, "London", false)));
        QuizItem item2 =
                QuizItem.of(
                        2,
                        TOPIC,
                        "2+2=?",
                        Optional.empty(),
                        List.of(
                                new QuizItemOption(1, "4", true),
                                new QuizItemOption(2, "5", false)));
        return Content.rehydrate(
                ContentId.of(42L),
                TOPIC,
                "Sample quiz",
                ContentType.QUIZ,
                Difficulty.BEGINNER,
                10,
                "https://example.com/q",
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK),
                List.of(item1, item2));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void getAttemptRendersItemsWithoutIsCorrect() throws Exception {
        when(contentRepository.findById(ContentId.of(42L))).thenReturn(Optional.of(sampleQuiz()));

        mockMvc.perform(get("/quiz/42/attempt"))
                .andExpect(status().isOk())
                .andExpect(view().name("quiz/attempt"))
                .andExpect(content().string(containsString("Capital of France")))
                .andExpect(content().string(containsString("Paris")))
                .andExpect(content().string(containsString("London")))
                // Critical security: NO indicator of which option is correct
                // anywhere in the rendered HTML.
                .andExpect(content().string(not(containsString("isCorrect"))))
                .andExpect(content().string(not(containsString("is_correct"))))
                .andExpect(content().string(not(containsString("correct=true"))))
                .andExpect(content().string(not(containsString("correct=\"true\""))));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void getAttempt404OnNonQuizContent() throws Exception {
        // Build a VIDEO content (not a quiz) and stub findById to return it.
        Content video =
                Content.rehydrate(
                        ContentId.of(7L),
                        TOPIC,
                        "Video",
                        ContentType.VIDEO,
                        Difficulty.BEGINNER,
                        10,
                        "https://x.y",
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        AuditFields.initial("system", CLOCK));
        when(contentRepository.findById(ContentId.of(7L))).thenReturn(Optional.of(video));

        mockMvc.perform(get("/quiz/7/attempt")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "INSTRUCTOR")
    void getAttempt403ForNonStudent() throws Exception {
        mockMvc.perform(get("/quiz/42/attempt")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postAttemptRendersResultViewWithScore() throws Exception {
        Content quiz = sampleQuiz();
        when(contentRepository.findById(ContentId.of(42L))).thenReturn(Optional.of(quiz));
        when(useCase.handle(any()))
                .thenReturn(
                        new SubmitQuizAttemptResult(
                                99L,
                                new BigDecimal("50.00"),
                                1,
                                2,
                                List.of(
                                        new PerItemFeedback(1, 1, 1, true, TOPIC),
                                        new PerItemFeedback(2, 2, 1, false, TOPIC)),
                                List.of()));

        mockMvc.perform(post("/quiz/42/attempt").param("answers[1]", "1").param("answers[2]", "2"))
                .andExpect(status().isOk())
                .andExpect(view().name("quiz/result"))
                .andExpect(content().string(containsString("You scored")))
                .andExpect(content().string(containsString("50.00")))
                .andExpect(content().string(containsString("Correct")))
                .andExpect(content().string(containsString("Incorrect")));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void getAttemptHtmlContainsAllOptionTextButNoCorrectnessAttribute() throws Exception {
        // Extended security check: the correct option text "Paris" / "4"
        // appears (it's one of the displayed options), but no attribute
        // marks WHICH is correct.
        when(contentRepository.findById(ContentId.of(42L))).thenReturn(Optional.of(sampleQuiz()));

        mockMvc.perform(get("/quiz/42/attempt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Paris")))
                .andExpect(content().string(containsString("London")))
                .andExpect(content().string(containsString(">4<")))
                .andExpect(content().string(containsString(">5<")))
                .andExpect(
                        content()
                                .string(
                                        not(
                                                containsString(
                                                        "data-correct"))));
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
