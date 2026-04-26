package com.plrs.web.path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.path.GeneratePathUseCase;
import com.plrs.application.path.MarkPathStepDoneUseCase;
import com.plrs.application.security.TokenService;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathRepository;
import com.plrs.domain.path.LearnerPathStatus;
import com.plrs.domain.path.LearnerPathStep;
import com.plrs.domain.path.PathId;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
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

@WebMvcTest(controllers = PathViewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(PathViewControllerTest.MethodSecurityTestConfig.class)
class PathViewControllerTest {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";
    private static final TopicId TOPIC = TopicId.of(7L);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC);

    @Autowired private MockMvc mockMvc;

    @MockBean private GeneratePathUseCase generateUseCase;
    @MockBean private MarkPathStepDoneUseCase markStepDoneUseCase;
    @MockBean private LearnerPathRepository pathRepository;
    @MockBean private ContentRepository contentRepository;
    @MockBean private TopicRepository topicRepository;
    @MockBean private TokenService tokenService;

    private static Content makeContent(long id, String title) {
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

    private LearnerPath sample() {
        return LearnerPath.rehydrate(
                PathId.of(99L),
                UserId.of(UUID.fromString(STUDENT_UUID)),
                TOPIC,
                LearnerPathStatus.IN_PROGRESS,
                Instant.parse("2026-04-26T09:00:00Z"),
                Optional.of(Instant.parse("2026-04-26T09:30:00Z")),
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
    void generateFormRendersTopicSelect() throws Exception {
        when(topicRepository.findRootTopics())
                .thenReturn(
                        List.of(
                                Topic.rehydrate(
                                        TOPIC,
                                        "Algebra",
                                        "desc",
                                        Optional.empty(),
                                        AuditFields.initial("system", CLOCK))));

        mockMvc.perform(get("/path/generate"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Algebra")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Generate a learning path")));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void generatePostRedirectsToPathView() throws Exception {
        when(generateUseCase.handle(any(), eq(TOPIC))).thenReturn(PathId.of(99L));

        mockMvc.perform(post("/path/generate").param("targetTopicId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/path/99"));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void viewRendersStepsWithMarkDoneButtonOnPending() throws Exception {
        when(pathRepository.findById(PathId.of(99L))).thenReturn(Optional.of(sample()));
        when(contentRepository.findById(ContentId.of(101L)))
                .thenReturn(Optional.of(makeContent(101L, "Step Title")));
        when(topicRepository.findById(TOPIC))
                .thenReturn(
                        Optional.of(
                                Topic.rehydrate(
                                        TOPIC,
                                        "Algebra",
                                        "desc",
                                        Optional.empty(),
                                        AuditFields.initial("system", CLOCK))));

        mockMvc.perform(get("/path/99"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Algebra")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Step Title")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Mark done")));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void markStepDonePostRedirectsBack() throws Exception {
        mockMvc.perform(post("/path/99/steps/1/done"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/path/99"));
        verify(markStepDoneUseCase).handle(PathId.of(99L), 1);
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
