package com.plrs.web.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.content.AddPrerequisiteCommand;
import com.plrs.application.content.AddPrerequisiteUseCase;
import com.plrs.application.content.ContentTitleNotUniqueException;
import com.plrs.application.content.CreateContentCommand;
import com.plrs.application.content.CreateContentUseCase;
import com.plrs.application.security.TokenService;
import com.plrs.application.topic.TopicNotFoundException;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.CycleDetectedException;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.topic.TopicId;
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
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = ContentController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = com.plrs.web.common.GlobalExceptionHandler.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(ContentControllerTest.MethodSecurityTestConfig.class)
class ContentControllerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);
    private static final String AUTHOR_UUID = "00000000-0000-0000-0000-000000000001";

    @Autowired private MockMvc mockMvc;

    @MockBean private CreateContentUseCase createContentUseCase;
    @MockBean private AddPrerequisiteUseCase addPrereqUseCase;
    @MockBean private ContentRepository contentRepository;
    @MockBean private PrerequisiteRepository prereqRepository;
    @MockBean private TokenService tokenService;

    private static Content sampleContent(long id, ContentType ctype) {
        return Content.rehydrate(
                ContentId.of(id),
                TopicId.of(1L),
                "Intro",
                ctype,
                Difficulty.BEGINNER,
                10,
                "https://example.com/" + id,
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private static String validContentBody() {
        return """
                {
                  "topicId": 1,
                  "title": "Intro",
                  "ctype": "VIDEO",
                  "difficulty": "BEGINNER",
                  "estMinutes": 10,
                  "url": "https://example.com/v",
                  "description": "desc",
                  "tags": ["warmup"]
                }
                """;
    }

    @Test
    @WithMockUser(username = AUTHOR_UUID, roles = "INSTRUCTOR")
    void postAsInstructor201WithValidContent() throws Exception {
        when(createContentUseCase.handle(any(CreateContentCommand.class)))
                .thenReturn(ContentId.of(99L));

        mockMvc.perform(
                        post("/api/content")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validContentBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/content/99"))
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.title").value("Intro"));
    }

    @Test
    @WithMockUser(username = AUTHOR_UUID, roles = "INSTRUCTOR")
    void postWithQuizCtypeReturns400() throws Exception {
        when(createContentUseCase.handle(any()))
                .thenThrow(new IllegalArgumentException(
                        "Use AuthorQuizUseCase for QUIZ ctype (step 81)"));

        mockMvc.perform(
                        post("/api/content")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validContentBody().replace("VIDEO", "QUIZ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("AuthorQuizUseCase")));
    }

    @Test
    @WithMockUser(username = AUTHOR_UUID, roles = "INSTRUCTOR")
    void postWithUnknownTopicReturns404() throws Exception {
        when(createContentUseCase.handle(any()))
                .thenThrow(new TopicNotFoundException(TopicId.of(1L)));

        mockMvc.perform(
                        post("/api/content")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validContentBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Topic not found"))
                .andExpect(jsonPath("$.topicId").value(1));
    }

    @Test
    @WithMockUser(username = AUTHOR_UUID, roles = "STUDENT")
    void postAsStudentReturns403() throws Exception {
        mockMvc.perform(
                        post("/api/content")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validContentBody()))
                .andExpect(status().isForbidden());

        verify(createContentUseCase, never()).handle(any());
    }

    @Test
    @WithMockUser(username = AUTHOR_UUID, roles = "INSTRUCTOR")
    void postDuplicateTitleInTopicReturns409() throws Exception {
        when(createContentUseCase.handle(any()))
                .thenThrow(new ContentTitleNotUniqueException(TopicId.of(1L), "Intro"));

        mockMvc.perform(
                        post("/api/content")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validContentBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Content title not unique within topic"))
                .andExpect(jsonPath("$.topicId").value(1))
                .andExpect(jsonPath("$.contentTitle").value("Intro"));
    }

    @Test
    @WithMockUser
    void getByIdReturnsWithPrereqsAndDeps() throws Exception {
        Content c = sampleContent(7L, ContentType.VIDEO);
        when(contentRepository.findById(ContentId.of(7L))).thenReturn(Optional.of(c));
        when(prereqRepository.findDirectPrerequisitesOf(c.id()))
                .thenReturn(
                        List.of(
                                new PrerequisiteEdge(
                                        c.id(),
                                        ContentId.of(2L),
                                        Instant.parse("2026-04-25T09:00:00Z"),
                                        Optional.empty())));
        when(prereqRepository.findDirectDependentsOf(c.id()))
                .thenReturn(
                        List.of(
                                new PrerequisiteEdge(
                                        ContentId.of(8L),
                                        c.id(),
                                        Instant.parse("2026-04-25T08:00:00Z"),
                                        Optional.empty())));

        mockMvc.perform(get("/api/content/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.ctype").value("VIDEO"))
                .andExpect(jsonPath("$.prerequisites.length()").value(1))
                .andExpect(jsonPath("$.prerequisites[0].prereqContentId").value(2))
                .andExpect(jsonPath("$.dependents.length()").value(1))
                .andExpect(jsonPath("$.dependents[0].contentId").value(8));
    }

    @Test
    @WithMockUser(username = AUTHOR_UUID, roles = "INSTRUCTOR")
    void postPrereqAsInstructorReturns201() throws Exception {
        doNothing().when(addPrereqUseCase).handle(any(AddPrerequisiteCommand.class));

        mockMvc.perform(
                        post("/api/content/1/prerequisites")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"prereqContentId\":2}"))
                .andExpect(status().isCreated());

        verify(addPrereqUseCase).handle(any());
    }

    @Test
    @WithMockUser(username = AUTHOR_UUID, roles = "INSTRUCTOR")
    void postPrereqCycleDetectedReturns409WithPath() throws Exception {
        doThrow(
                        new CycleDetectedException(
                                ContentId.of(1L),
                                ContentId.of(2L),
                                List.of(ContentId.of(2L), ContentId.of(3L), ContentId.of(1L))))
                .when(addPrereqUseCase)
                .handle(any());

        mockMvc.perform(
                        post("/api/content/1/prerequisites")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"prereqContentId\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Prerequisite cycle detected"))
                .andExpect(jsonPath("$.cyclePath.length()").value(3))
                .andExpect(jsonPath("$.cyclePath[0]").value(2))
                .andExpect(jsonPath("$.cyclePath[1]").value(3))
                .andExpect(jsonPath("$.cyclePath[2]").value(1));
    }

    @Test
    @WithMockUser(username = AUTHOR_UUID, roles = "INSTRUCTOR")
    void postPrereqSelfEdgeReturns409() throws Exception {
        doThrow(
                        new CycleDetectedException(
                                ContentId.of(1L),
                                ContentId.of(1L),
                                List.of(ContentId.of(1L))))
                .when(addPrereqUseCase)
                .handle(any());

        mockMvc.perform(
                        post("/api/content/1/prerequisites")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"prereqContentId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.cyclePath.length()").value(1))
                .andExpect(jsonPath("$.cyclePath[0]").value(1));
    }

    @Test
    @WithMockUser(username = AUTHOR_UUID, roles = "STUDENT")
    void postPrereqAsStudentReturns403() throws Exception {
        mockMvc.perform(
                        post("/api/content/1/prerequisites")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"prereqContentId\":2}"))
                .andExpect(status().isForbidden());

        verify(addPrereqUseCase, never()).handle(any());
    }

    @Test
    @WithMockUser(username = AUTHOR_UUID, roles = "INSTRUCTOR")
    void postPrereqIdempotentReturns201() throws Exception {
        // Use case is idempotent (returns silently on existing edge);
        // controller still returns 201 — the resource is present post-op.
        doNothing().when(addPrereqUseCase).handle(any());

        mockMvc.perform(
                        post("/api/content/1/prerequisites")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"prereqContentId\":2}"))
                .andExpect(status().isCreated());
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
