package com.plrs.web.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.cache.TopNCache;
import com.plrs.application.content.AddPrerequisiteUseCase;
import com.plrs.application.content.CreateContentUseCase;
import com.plrs.application.dashboard.StudentDashboardService;
import com.plrs.application.dashboard.StudentDashboardView;
import com.plrs.application.dashboard.WeeklyActivityService;
import com.plrs.application.interaction.RecordInteractionResult;
import com.plrs.application.interaction.RecordInteractionUseCase;
import com.plrs.application.outbox.OutboxRepository;
import com.plrs.application.quiz.AdvisoryLockService;
import com.plrs.application.quiz.SubmitQuizAttemptResult;
import com.plrs.application.quiz.SubmitQuizAttemptUseCase;
import com.plrs.application.security.RefreshTokenStore;
import com.plrs.application.security.TokenService;
import com.plrs.application.topic.CreateTopicUseCase;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.quiz.QuizAttemptRepository;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserRepository;
import com.plrs.web.catalog.ContentController;
import com.plrs.web.catalog.TopicController;
import com.plrs.web.common.GlobalExceptionHandler;
import com.plrs.web.dashboard.DashboardApiController;
import com.plrs.web.dashboard.DashboardController;
import com.plrs.web.interaction.InteractionController;
import com.plrs.web.interaction.WebInteractionController;
import com.plrs.web.quiz.QuizAttemptController;
import com.plrs.web.quiz.QuizAttemptViewController;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * Cross-controller authorization matrix for the mutating Iter-2
 * endpoints (§4.a.3.1 RBAC, FR-05). For each endpoint listed in the
 * step-96 spec, exercises one allowed-role case (asserts the request
 * was not blocked by the security chain) and one denied-role case
 * (asserts 403). Anonymous probes for representative endpoints round
 * out the matrix at ≥18 (endpoint, role) pairs.
 *
 * <p>Wires through the real {@link SecurityConfig} via
 * {@code @Import}; controllers are real but their downstream use-case
 * beans are mocked so the matrix isolates the authorization gate from
 * business behaviour. {@code @SpringBootTest} would equivalently work
 * with TestRestTemplate per the spec but would also drag in JPA /
 * Redis auto-config; the slice is faster, equally rigorous for the
 * authorization concern, and matches the existing
 * {@code SecurityConfigTest} pattern in this module.
 *
 * <p>"Allowed" assertions check that the response is neither 401 nor
 * 403 — a 4xx body-validation status from a downstream parsing failure
 * is acceptable, since it proves the security gate let the call
 * through.
 */
@WebMvcTest(
        controllers = {
            TopicController.class,
            ContentController.class,
            InteractionController.class,
            WebInteractionController.class,
            QuizAttemptController.class,
            QuizAttemptViewController.class,
            DashboardController.class,
            DashboardApiController.class
        },
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = GlobalExceptionHandler.class))
@Import({SecurityConfig.class, PasswordEncoderBridge.class, JwtAuthenticationFilter.class})
class RoleMatrixIT {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String INSTRUCTOR_UUID = "22222222-3333-4444-5555-666666666666";

    @Autowired private MockMvc mockMvc;

    @MockBean private TokenService tokenService;
    @MockBean private RefreshTokenStore refreshTokenStore;
    @MockBean private UserRepository userRepository;
    @MockBean private TopicRepository topicRepository;
    @MockBean private ContentRepository contentRepository;
    @MockBean private PrerequisiteRepository prerequisiteRepository;
    @MockBean private InteractionRepository interactionRepository;
    @MockBean private UserSkillRepository userSkillRepository;
    @MockBean private QuizAttemptRepository quizAttemptRepository;
    @MockBean private OutboxRepository outboxRepository;
    @MockBean private AdvisoryLockService advisoryLockService;
    @MockBean private TopNCache topNCache;
    @MockBean private CreateTopicUseCase createTopicUseCase;
    @MockBean private CreateContentUseCase createContentUseCase;
    @MockBean private AddPrerequisiteUseCase addPrerequisiteUseCase;
    @MockBean private RecordInteractionUseCase recordInteractionUseCase;
    @MockBean private SubmitQuizAttemptUseCase submitQuizAttemptUseCase;
    @MockBean private StudentDashboardService dashboardService;
    @MockBean private WeeklyActivityService weeklyActivityService;
    @MockBean private com.plrs.domain.path.LearnerPathRepository learnerPathRepository;

    private static UUID newId() {
        return UUID.randomUUID();
    }

    private void assertAllowed(ResultActions res) throws Exception {
        int status = res.andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status)
                .as("expected security chain to allow; got %d", status)
                .isNotIn(401, 403);
    }

    private void assertForbidden(ResultActions res) throws Exception {
        res.andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isForbidden());
    }

    private void assertUnauthorized(ResultActions res) throws Exception {
        res.andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isUnauthorized());
    }

    private RequestBuilder studentApiPost(String path, String body) {
        return post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(user(STUDENT_UUID).roles("STUDENT"));
    }

    private RequestBuilder instructorApiPost(String path, String body) {
        return post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(user(INSTRUCTOR_UUID).roles("INSTRUCTOR"));
    }

    // -- POST /api/topics ----------------------------------------------------

    @Test
    void postApiTopicsAsInstructorAllowed() throws Exception {
        Mockito.when(createTopicUseCase.handle(ArgumentMatchers.any()))
                .thenReturn(TopicId.of(1L));
        assertAllowed(
                mockMvc.perform(instructorApiPost("/api/topics", "{\"name\":\"Algebra\"}")));
    }

    @Test
    void postApiTopicsAsStudent403() throws Exception {
        assertForbidden(
                mockMvc.perform(studentApiPost("/api/topics", "{\"name\":\"Algebra\"}")));
    }

    // -- POST /api/content ---------------------------------------------------

    @Test
    void postApiContentAsInstructorAllowed() throws Exception {
        Mockito.when(createContentUseCase.handle(ArgumentMatchers.any()))
                .thenReturn(ContentId.of(1L));
        assertAllowed(
                mockMvc.perform(
                        instructorApiPost(
                                "/api/content",
                                "{\"topicId\":1,\"title\":\"x\",\"ctype\":\"VIDEO\","
                                        + "\"difficulty\":\"BEGINNER\",\"estMinutes\":5,"
                                        + "\"url\":\"https://x.y\"}")));
    }

    @Test
    void postApiContentAsStudent403() throws Exception {
        assertForbidden(
                mockMvc.perform(
                        studentApiPost(
                                "/api/content",
                                "{\"topicId\":1,\"title\":\"x\",\"ctype\":\"VIDEO\","
                                        + "\"difficulty\":\"BEGINNER\",\"estMinutes\":5,"
                                        + "\"url\":\"https://x.y\"}")));
    }

    // -- POST /api/content/{id}/prerequisites --------------------------------

    @Test
    void postApiPrerequisitesAsInstructorAllowed() throws Exception {
        assertAllowed(
                mockMvc.perform(
                        instructorApiPost(
                                "/api/content/1/prerequisites",
                                "{\"prereqContentId\":2}")));
    }

    @Test
    void postApiPrerequisitesAsStudent403() throws Exception {
        assertForbidden(
                mockMvc.perform(
                        studentApiPost(
                                "/api/content/1/prerequisites",
                                "{\"prereqContentId\":2}")));
    }

    // -- POST /api/interactions ----------------------------------------------

    @Test
    void postApiInteractionsAsStudentAllowed() throws Exception {
        Mockito.when(recordInteractionUseCase.handle(ArgumentMatchers.any()))
                .thenReturn(RecordInteractionResult.RECORDED);
        assertAllowed(
                mockMvc.perform(
                        studentApiPost(
                                "/api/interactions",
                                "{\"contentId\":1,\"eventType\":\"VIEW\"}")));
    }

    @Test
    void postApiInteractionsAsInstructor403() throws Exception {
        assertForbidden(
                mockMvc.perform(
                        instructorApiPost(
                                "/api/interactions",
                                "{\"contentId\":1,\"eventType\":\"VIEW\"}")));
    }

    // -- POST /web-api/interactions (form chain, CSRF required) --------------

    @Test
    void postWebApiInteractionsAsStudentAllowed() throws Exception {
        Mockito.when(recordInteractionUseCase.handle(ArgumentMatchers.any()))
                .thenReturn(RecordInteractionResult.RECORDED);
        assertAllowed(
                mockMvc.perform(
                        post("/web-api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentId\":1,\"eventType\":\"VIEW\"}")
                                .with(user(STUDENT_UUID).roles("STUDENT"))
                                .with(csrf())));
    }

    @Test
    void postWebApiInteractionsAsInstructor403() throws Exception {
        assertForbidden(
                mockMvc.perform(
                        post("/web-api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentId\":1,\"eventType\":\"VIEW\"}")
                                .with(user(INSTRUCTOR_UUID).roles("INSTRUCTOR"))
                                .with(csrf())));
    }

    // -- POST /api/quiz-attempts ---------------------------------------------

    @Test
    void postApiQuizAttemptsAsStudentAllowed() throws Exception {
        Mockito.when(submitQuizAttemptUseCase.handle(ArgumentMatchers.any()))
                .thenReturn(
                        new SubmitQuizAttemptResult(
                                7L,
                                new BigDecimal("0.00"),
                                0,
                                0,
                                List.of(),
                                List.of()));
        assertAllowed(
                mockMvc.perform(
                        studentApiPost(
                                "/api/quiz-attempts",
                                "{\"quizContentId\":42,\"answers\":[]}")));
    }

    @Test
    void postApiQuizAttemptsAsInstructor403() throws Exception {
        assertForbidden(
                mockMvc.perform(
                        instructorApiPost(
                                "/api/quiz-attempts",
                                "{\"quizContentId\":42,\"answers\":[]}")));
    }

    // -- POST /quiz/{id}/attempt (form chain) --------------------------------

    @Test
    void postQuizAttemptFormAsStudentAllowed() throws Exception {
        Mockito.when(submitQuizAttemptUseCase.handle(ArgumentMatchers.any()))
                .thenReturn(
                        new SubmitQuizAttemptResult(
                                7L,
                                new BigDecimal("0.00"),
                                0,
                                0,
                                List.of(),
                                List.of()));
        assertAllowed(
                mockMvc.perform(
                        post("/quiz/42/attempt")
                                .with(user(STUDENT_UUID).roles("STUDENT"))
                                .with(csrf())));
    }

    @Test
    void postQuizAttemptFormAsInstructor403() throws Exception {
        assertForbidden(
                mockMvc.perform(
                        post("/quiz/42/attempt")
                                .with(user(INSTRUCTOR_UUID).roles("INSTRUCTOR"))
                                .with(csrf())));
    }

    // -- GET /dashboard ------------------------------------------------------

    @Test
    void getDashboardAsStudentAllowed() throws Exception {
        Mockito.when(dashboardService.load(ArgumentMatchers.any()))
                .thenReturn(new StudentDashboardView(List.of(), List.of(), List.of()));
        assertAllowed(
                mockMvc.perform(
                        get("/dashboard").with(user(STUDENT_UUID).roles("STUDENT"))));
    }

    @Test
    void getDashboardAsInstructor403() throws Exception {
        assertForbidden(
                mockMvc.perform(
                        get("/dashboard").with(user(INSTRUCTOR_UUID).roles("INSTRUCTOR"))));
    }

    // -- GET /web-api/me/activity-weekly -------------------------------------

    @Test
    void getWebApiActivityWeeklyAsStudentAllowed() throws Exception {
        Mockito.when(weeklyActivityService.last8Weeks(ArgumentMatchers.any()))
                .thenReturn(List.of());
        assertAllowed(
                mockMvc.perform(
                        get("/web-api/me/activity-weekly")
                                .with(user(STUDENT_UUID).roles("STUDENT"))));
    }

    @Test
    void getWebApiActivityWeeklyAsInstructor403() throws Exception {
        assertForbidden(
                mockMvc.perform(
                        get("/web-api/me/activity-weekly")
                                .with(user(INSTRUCTOR_UUID).roles("INSTRUCTOR"))));
    }

    // -- Anonymous probes (api → 401, web → redirect) ------------------------

    @Test
    void postApiTopicsAnonymous401() throws Exception {
        assertUnauthorized(
                mockMvc.perform(
                        post("/api/topics")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")));
    }

    @Test
    void postApiQuizAttemptsAnonymous401() throws Exception {
        assertUnauthorized(
                mockMvc.perform(
                        post("/api/quiz-attempts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")));
    }
}
