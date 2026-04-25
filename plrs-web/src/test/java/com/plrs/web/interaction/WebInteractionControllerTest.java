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
        controllers = WebInteractionController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = GlobalExceptionHandler.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(WebInteractionControllerTest.MethodSecurityTestConfig.class)
class WebInteractionControllerTest {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";

    @Autowired private MockMvc mockMvc;

    @MockBean private RecordInteractionUseCase useCase;
    @MockBean private TokenService tokenService;

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postAsStudentWithSessionReturns201() throws Exception {
        when(useCase.handle(any(RecordInteractionCommand.class)))
                .thenReturn(RecordInteractionResult.RECORDED);

        mockMvc.perform(
                        post("/web-api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentId\":42,\"eventType\":\"VIEW\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("RECORDED"));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "INSTRUCTOR")
    void postAsInstructorReturns403() throws Exception {
        mockMvc.perform(
                        post("/web-api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentId\":42,\"eventType\":\"VIEW\"}"))
                .andExpect(status().isForbidden());

        verify(useCase, never()).handle(any());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void postDebouncedReturns200() throws Exception {
        // Replaces the spec's "without_session redirects 302" test: slice
        // tests with addFilters=false don't run the session filter so the
        // redirect path is unobservable. Asserting the DEBOUNCED HTTP
        // contract instead. The session-redirect surface is exercised by
        // the SecurityConfig integration test.
        when(useCase.handle(any())).thenReturn(RecordInteractionResult.DEBOUNCED);

        mockMvc.perform(
                        post("/web-api/interactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentId\":42,\"eventType\":\"VIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DEBOUNCED"));
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
