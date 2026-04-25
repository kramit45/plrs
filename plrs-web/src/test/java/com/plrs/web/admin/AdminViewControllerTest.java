package com.plrs.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.plrs.application.eval.EvalRun;
import com.plrs.application.eval.EvalRunRepository;
import com.plrs.application.eval.RunEvalUseCase;
import com.plrs.application.security.TokenService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
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

@WebMvcTest(controllers = AdminViewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AdminViewControllerTest.MethodSecurityTestConfig.class)
class AdminViewControllerTest {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";

    @Autowired private MockMvc mockMvc;

    @MockBean private RunEvalUseCase runEvalUseCase;
    @MockBean private EvalRunRepository evalRunRepository;
    @MockBean private TokenService tokenService;

    private static EvalRun fixture() {
        return new EvalRun(
                Optional.of(7L),
                Instant.parse("2026-04-25T10:00:00Z"),
                "hybrid_v1",
                (short) 10,
                Optional.of(new BigDecimal("0.4500")),
                Optional.of(new BigDecimal("0.5500")),
                Optional.of(new BigDecimal("0.3000")),
                Optional.of(12));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "ADMIN")
    void adminGetsHomeWithLatestEval() throws Exception {
        when(evalRunRepository.findLatest()).thenReturn(Optional.of(fixture()));

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/home"))
                .andExpect(model().attributeExists("latestEval"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hybrid_v1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("0.4500")));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "ADMIN")
    void adminGetsHomeWithEmptyState() throws Exception {
        when(evalRunRepository.findLatest()).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/home"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("No evaluation has been run yet")));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void studentIsForbiddenFromAdminHome() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(status().isForbidden());
        verify(evalRunRepository, never()).findLatest();
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "ADMIN")
    void adminTriggersEvalAndIsRedirectedHome() throws Exception {
        when(runEvalUseCase.handle(anyString(), anyInt())).thenReturn(fixture());

        mockMvc.perform(post("/admin/eval/run"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));

        verify(runEvalUseCase).handle("hybrid_v1", 10);
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void studentIsForbiddenFromTriggeringEval() throws Exception {
        mockMvc.perform(post("/admin/eval/run")).andExpect(status().isForbidden());
        verify(runEvalUseCase, never()).handle(any(), anyInt());
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
