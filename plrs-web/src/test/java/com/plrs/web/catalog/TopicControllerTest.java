package com.plrs.web.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.security.TokenService;
import com.plrs.application.topic.CreateTopicCommand;
import com.plrs.application.topic.CreateTopicUseCase;
import com.plrs.application.topic.TopicAlreadyExistsException;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.web.common.GlobalExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
        controllers = TopicController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = GlobalExceptionHandler.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(TopicControllerTest.MethodSecurityTestConfig.class)
class TopicControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);

    @Autowired private MockMvc mockMvc;

    @MockBean private CreateTopicUseCase createTopicUseCase;
    @MockBean private TopicRepository topicRepository;
    @MockBean private TokenService tokenService;

    private static Topic persisted(long id, String name, Long parentId) {
        return Topic.rehydrate(
                TopicId.of(id),
                name,
                "desc",
                parentId == null ? Optional.empty() : Optional.of(TopicId.of(parentId)),
                AuditFields.initial("system", CLOCK));
    }

    @Test
    @WithMockUser(roles = "INSTRUCTOR")
    void postAsInstructorReturns201() throws Exception {
        when(createTopicUseCase.handle(any(CreateTopicCommand.class)))
                .thenReturn(TopicId.of(42L));

        mockMvc.perform(
                        post("/api/topics")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Algebra\",\"description\":\"intro\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/topics/42"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.name").value("Algebra"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postAsAdminReturns201() throws Exception {
        when(createTopicUseCase.handle(any())).thenReturn(TopicId.of(43L));

        mockMvc.perform(
                        post("/api/topics")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Geometry\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void postAsStudentReturns403() throws Exception {
        mockMvc.perform(
                        post("/api/topics")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Algebra\"}"))
                .andExpect(status().isForbidden());

        verify(createTopicUseCase, never()).handle(any());
    }

    @Test
    @WithMockUser(roles = "INSTRUCTOR")
    void postBlankNameReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/topics")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());

        verify(createTopicUseCase, never()).handle(any());
    }

    @Test
    @WithMockUser(roles = "INSTRUCTOR")
    void postDuplicateNameReturns409() throws Exception {
        when(createTopicUseCase.handle(any()))
                .thenThrow(new TopicAlreadyExistsException("Algebra"));

        mockMvc.perform(
                        post("/api/topics")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Algebra\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Topic already exists"))
                .andExpect(jsonPath("$.name").value("Algebra"));
    }

    @Test
    @WithMockUser
    void getByIdReturns200() throws Exception {
        when(topicRepository.findById(TopicId.of(7L)))
                .thenReturn(Optional.of(persisted(7L, "Algebra", null)));

        mockMvc.perform(get("/api/topics/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Algebra"))
                .andExpect(jsonPath("$.parentTopicId").doesNotExist());
    }

    @Test
    @WithMockUser
    void getByIdNotFoundReturns404() throws Exception {
        when(topicRepository.findById(TopicId.of(7L))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/topics/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Topic not found"))
                .andExpect(jsonPath("$.topicId").value(7));
    }

    @Test
    @WithMockUser
    void listRootTopicsReturnsRootsOnly() throws Exception {
        when(topicRepository.findRootTopics())
                .thenReturn(List.of(persisted(1L, "Algebra", null), persisted(2L, "Calculus", null)));

        mockMvc.perform(get("/api/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Algebra"))
                .andExpect(jsonPath("$[1].name").value("Calculus"));
    }

    @Test
    @WithMockUser
    void listChildrenOfParent() throws Exception {
        when(topicRepository.findChildrenOf(TopicId.of(1L)))
                .thenReturn(List.of(persisted(10L, "Linear", 1L)));

        mockMvc.perform(get("/api/topics").param("parentId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Linear"))
                .andExpect(jsonPath("$[0].parentTopicId").value(1));
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
