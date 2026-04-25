package com.plrs.web.interaction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.interaction.RecordInteractionCommand;
import com.plrs.application.interaction.RecordInteractionResult;
import com.plrs.application.interaction.RecordInteractionUseCase;
import com.plrs.application.security.TokenService;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.web.common.GlobalExceptionHandler;
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
        controllers = InteractionController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = GlobalExceptionHandler.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(InteractionControllerTest.MethodSecurityTestConfig.class)
class InteractionControllerTest {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";

    @Autowired private MockMvc mockMvc;

    @MockBean private RecordInteractionUseCase useCase;
    @MockBean private TokenService tokenService;

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postAsStudentWithValidViewReturns201Recorded() throws Exception {
        when(useCase.handle(any(RecordInteractionCommand.class)))
                .thenReturn(RecordInteractionResult.RECORDED);

        mockMvc.perform(
                        post("/api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentId\":42,\"eventType\":\"VIEW\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("RECORDED"));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postWithNoRecentViewReturns201() throws Exception {
        when(useCase.handle(any())).thenReturn(RecordInteractionResult.RECORDED);

        mockMvc.perform(
                        post("/api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"contentId\":42,\"eventType\":\"VIEW\",\"dwellSec\":15}"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postAfterRecentViewReturns200Debounced() throws Exception {
        when(useCase.handle(any())).thenReturn(RecordInteractionResult.DEBOUNCED);

        mockMvc.perform(
                        post("/api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentId\":42,\"eventType\":\"VIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DEBOUNCED"));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "INSTRUCTOR")
    void postAsInstructorReturns403() throws Exception {
        mockMvc.perform(
                        post("/api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentId\":42,\"eventType\":\"VIEW\"}"))
                .andExpect(status().isForbidden());

        verify(useCase, never()).handle(any());
    }

    @Test
    void postUnauthenticatedReturns401() throws Exception {
        // No @WithMockUser → SecurityContext has no Authentication →
        // method-security throws AuthenticationCredentialsNotFoundException
        // (an AuthenticationException) → handled by the global advice as 401.
        mockMvc.perform(
                        post("/api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentId\":42,\"eventType\":\"VIEW\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postInvalidEventTypeReturns400() throws Exception {
        when(useCase.handle(any()))
                .thenThrow(new DomainValidationException("Unknown event type: 'SHARE'"));

        mockMvc.perform(
                        post("/api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentId\":42,\"eventType\":\"SHARE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postRateWithoutRatingReturns400() throws Exception {
        when(useCase.handle(any()))
                .thenThrow(new DomainValidationException("RATE requires rating"));

        mockMvc.perform(
                        post("/api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentId\":42,\"eventType\":\"RATE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postBookmarkWithDwellReturns400() throws Exception {
        when(useCase.handle(any()))
                .thenThrow(
                        new DomainValidationException(
                                "dwell_sec only permitted for VIEW/COMPLETE, got BOOKMARK"));

        mockMvc.perform(
                        post("/api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"contentId\":42,\"eventType\":\"BOOKMARK\",\"dwellSec\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("dwell_sec")));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postRatingOutOfRangeReturns400() throws Exception {
        // @Min(1) @Max(5) on the request — bean validation handles this
        // before the use case is invoked.
        mockMvc.perform(
                        post("/api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"contentId\":42,\"eventType\":\"RATE\",\"rating\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rating").exists());

        verify(useCase, never()).handle(any());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postClientInfoOver200CharsReturns400() throws Exception {
        String longInfo = "x".repeat(201);
        String body =
                "{\"contentId\":42,\"eventType\":\"VIEW\",\"clientInfo\":\""
                        + longInfo
                        + "\"}";

        mockMvc.perform(
                        post("/api/interactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.clientInfo").exists());

        verify(useCase, never()).handle(any());
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
