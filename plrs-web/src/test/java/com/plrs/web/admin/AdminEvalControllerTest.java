package com.plrs.web.admin;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.eval.EvalRun;
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

@WebMvcTest(controllers = AdminEvalController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AdminEvalControllerTest.MethodSecurityTestConfig.class)
class AdminEvalControllerTest {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";

    @Autowired private MockMvc mockMvc;

    @MockBean private RunEvalUseCase useCase;
    @MockBean private TokenService tokenService;

    private static EvalRun okRun() {
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
    void adminCanTriggerEvalRun() throws Exception {
        when(useCase.handle(anyString(), anyInt())).thenReturn(okRun());

        mockMvc.perform(post("/api/admin/eval/run").param("variant", "hybrid_v1").param("k", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evalRunSk").value(7))
                .andExpect(jsonPath("$.variant").value("hybrid_v1"))
                .andExpect(jsonPath("$.precisionAtK").value(0.45))
                .andExpect(jsonPath("$.ndcgAtK").value(0.55))
                .andExpect(jsonPath("$.coverage").value(0.30))
                .andExpect(jsonPath("$.nUsers").value(12));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void studentIsForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/eval/run"))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
