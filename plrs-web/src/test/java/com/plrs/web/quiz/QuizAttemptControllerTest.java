package com.plrs.web.quiz;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.content.ContentNotFoundException;
import com.plrs.application.quiz.SubmitQuizAttemptCommand;
import com.plrs.application.quiz.SubmitQuizAttemptResult;
import com.plrs.application.quiz.SubmitQuizAttemptUseCase;
import com.plrs.application.security.TokenService;
import com.plrs.domain.content.ContentId;
import java.math.BigDecimal;
import java.util.List;
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
        controllers = QuizAttemptController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = com.plrs.web.common.GlobalExceptionHandler.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(QuizAttemptControllerTest.MethodSecurityTestConfig.class)
class QuizAttemptControllerTest {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";

    @Autowired private MockMvc mockMvc;

    @MockBean private SubmitQuizAttemptUseCase useCase;
    @MockBean private TokenService tokenService;

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postApiAttemptAsStudent201() throws Exception {
        when(useCase.handle(any(SubmitQuizAttemptCommand.class)))
                .thenReturn(
                        new SubmitQuizAttemptResult(
                                99L,
                                new BigDecimal("100.00"),
                                2,
                                2,
                                List.of(),
                                List.of()));

        mockMvc.perform(
                        post("/api/quiz-attempts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"quizContentId\":42,\"answers\":[{\"itemOrder\":1,"
                                                + "\"selectedOptionOrder\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attemptId").value(99))
                .andExpect(jsonPath("$.score").value(100.00))
                .andExpect(jsonPath("$.correctCount").value(2))
                .andExpect(jsonPath("$.totalCount").value(2));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "INSTRUCTOR")
    void postApiAttemptAsInstructor403() throws Exception {
        mockMvc.perform(
                        post("/api/quiz-attempts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quizContentId\":42,\"answers\":[]}"))
                .andExpect(status().isForbidden());

        verify(useCase, never()).handle(any());
    }

    @Test
    void postApiAttemptUnauthenticated401() throws Exception {
        mockMvc.perform(
                        post("/api/quiz-attempts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quizContentId\":42,\"answers\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postApiAttemptInvalidPayload400() throws Exception {
        // Missing required quizContentId
        mockMvc.perform(
                        post("/api/quiz-attempts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"answers\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quizContentId").exists());

        verify(useCase, never()).handle(any());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postApiAttemptQuizNotFound404() throws Exception {
        when(useCase.handle(any())).thenThrow(new ContentNotFoundException(ContentId.of(42L)));

        mockMvc.perform(
                        post("/api/quiz-attempts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quizContentId\":42,\"answers\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Content not found"));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postApiAttemptNonQuizContent400() throws Exception {
        when(useCase.handle(any()))
                .thenThrow(new IllegalArgumentException("Content is not a quiz: 42"));

        mockMvc.perform(
                        post("/api/quiz-attempts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quizContentId\":42,\"answers\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("not a quiz")));
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
