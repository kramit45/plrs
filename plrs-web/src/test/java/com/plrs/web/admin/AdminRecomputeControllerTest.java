package com.plrs.web.admin;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.admin.RecomputeRecommender;
import com.plrs.application.security.TokenService;
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

@WebMvcTest(controllers = AdminRecomputeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AdminRecomputeControllerTest.MethodSecurityTestConfig.class)
class AdminRecomputeControllerTest {

    private static final String ANY_UUID = "11111111-2222-3333-4444-555555555555";

    @Autowired private MockMvc mockMvc;

    @MockBean private RecomputeRecommender recompute;
    @MockBean private TokenService tokenService;

    @Test
    @WithMockUser(username = ANY_UUID, roles = "ADMIN")
    void adminTriggersRecompute() throws Exception {
        mockMvc.perform(post("/api/admin/recommender/recompute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
        verify(recompute).recomputeNow();
    }

    @Test
    @WithMockUser(username = ANY_UUID, roles = "STUDENT")
    void studentIsForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/recommender/recompute"))
                .andExpect(status().isForbidden());
        verify(recompute, never()).recomputeNow();
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
