package com.plrs.web.path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.path.AbandonPathUseCase;
import com.plrs.application.path.GeneratePathUseCase;
import com.plrs.application.path.MarkPathStepDoneUseCase;
import com.plrs.application.path.PausePathUseCase;
import com.plrs.application.path.PathPlanner;
import com.plrs.application.path.ResumePathUseCase;
import com.plrs.application.path.StartPathUseCase;
import com.plrs.application.security.TokenService;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathDraft;
import com.plrs.domain.path.LearnerPathRepository;
import com.plrs.domain.path.LearnerPathStatus;
import com.plrs.domain.path.LearnerPathStep;
import com.plrs.domain.path.PathId;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PathController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(PathControllerTest.MethodSecurityTestConfig.class)
class PathControllerTest {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";
    private static final TopicId TOPIC = TopicId.of(7L);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC);

    @Autowired private MockMvc mockMvc;

    @MockBean private PathPlanner planner;
    @MockBean private GeneratePathUseCase generateUseCase;
    @MockBean private StartPathUseCase startUseCase;
    @MockBean private PausePathUseCase pauseUseCase;
    @MockBean private ResumePathUseCase resumeUseCase;
    @MockBean private AbandonPathUseCase abandonUseCase;
    @MockBean private MarkPathStepDoneUseCase markStepDoneUseCase;
    @MockBean private LearnerPathRepository pathRepository;
    @MockBean private ContentRepository contentRepository;
    @MockBean private TokenService tokenService;

    private static Content content(long id, String title) {
        return Content.rehydrate(
                ContentId.of(id),
                TOPIC,
                title,
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                "https://x.y/" + id,
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private LearnerPath persisted(LearnerPathStatus status) {
        return LearnerPath.rehydrate(
                PathId.of(99L),
                UserId.of(UUID.fromString(STUDENT_UUID)),
                TOPIC,
                status,
                Instant.parse("2026-04-26T09:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(LearnerPathStep.pending(1, ContentId.of(101L), false, "step")),
                Map.of(TOPIC, MasteryScore.ZERO),
                Optional.empty());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void getPreviewDoesNotPersist() throws Exception {
        LearnerPathDraft draft =
                new LearnerPathDraft(
                        UserId.of(UUID.fromString(STUDENT_UUID)),
                        TOPIC,
                        List.of(LearnerPathStep.pending(1, ContentId.of(101L), false, "step")),
                        Map.of(TOPIC, MasteryScore.ZERO));
        when(planner.plan(any(), eq(TOPIC))).thenReturn(draft);
        when(contentRepository.findById(ContentId.of(101L)))
                .thenReturn(Optional.of(content(101L, "T")));

        mockMvc.perform(get("/api/learning-path").param("targetTopicId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREVIEW"))
                .andExpect(jsonPath("$.steps[0].title").value("T"));

        verify(generateUseCase, never()).handle(any(), any());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void getPersistInvokesGenerateUseCase() throws Exception {
        when(generateUseCase.handle(any(), eq(TOPIC))).thenReturn(PathId.of(99L));
        when(pathRepository.findById(PathId.of(99L)))
                .thenReturn(Optional.of(persisted(LearnerPathStatus.NOT_STARTED)));
        when(contentRepository.findById(ContentId.of(101L)))
                .thenReturn(Optional.of(content(101L, "T")));

        mockMvc.perform(
                        get("/api/learning-path")
                                .param("targetTopicId", "7")
                                .param("persist", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pathId").value(99))
                .andExpect(jsonPath("$.status").value("NOT_STARTED"));

        verify(generateUseCase).handle(any(), eq(TOPIC));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void startReturns204() throws Exception {
        mockMvc.perform(post("/api/learning-path/99/start"))
                .andExpect(status().isNoContent());
        verify(startUseCase).handle(PathId.of(99L));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void pauseReturns204() throws Exception {
        mockMvc.perform(post("/api/learning-path/99/pause"))
                .andExpect(status().isNoContent());
        verify(pauseUseCase).handle(PathId.of(99L));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void resumeReturns204() throws Exception {
        mockMvc.perform(post("/api/learning-path/99/resume"))
                .andExpect(status().isNoContent());
        verify(resumeUseCase).handle(PathId.of(99L));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void abandonReturns204() throws Exception {
        mockMvc.perform(post("/api/learning-path/99/abandon"))
                .andExpect(status().isNoContent());
        verify(abandonUseCase).handle(PathId.of(99L));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void markStepDoneReturns204() throws Exception {
        mockMvc.perform(post("/api/learning-path/99/steps/1/done"))
                .andExpect(status().isNoContent());
        verify(markStepDoneUseCase).handle(PathId.of(99L), 1);
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void activeReturnsOnlyActiveStatuses() throws Exception {
        when(pathRepository.findRecentByUser(any(), anyInt()))
                .thenReturn(
                        List.of(
                                persisted(LearnerPathStatus.IN_PROGRESS),
                                persisted(LearnerPathStatus.COMPLETED)));
        when(contentRepository.findById(ContentId.of(101L)))
                .thenReturn(Optional.of(content(101L, "T")));

        mockMvc.perform(get("/api/learning-path/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("IN_PROGRESS"));
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
