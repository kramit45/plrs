package com.plrs.web.admin;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.admin.ConfigParam;
import com.plrs.application.admin.ConfigParamService;
import com.plrs.application.security.TokenService;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

@WebMvcTest(controllers = AdminConfigController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AdminConfigControllerTest.MethodSecurityTestConfig.class)
class AdminConfigControllerTest {

    private static final String ADMIN_UUID = "11111111-2222-3333-4444-555555555555";

    @Autowired private MockMvc mockMvc;
    @MockBean private ConfigParamService configService;
    @MockBean private TokenService tokenService;

    @Test
    @WithMockUser(username = ADMIN_UUID, roles = "ADMIN")
    void adminSeesParamsTable() throws Exception {
        when(configService.findAll())
                .thenReturn(
                        List.of(
                                new ConfigParam(
                                        "rec.lambda_blend",
                                        "0.65",
                                        "DOUBLE",
                                        Optional.of("CF/CB blend"),
                                        Instant.parse("2026-04-26T10:00:00Z"),
                                        Optional.empty())));
        mockMvc.perform(get("/admin/config"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rec.lambda_blend")))
                .andExpect(content().string(containsString("config-table")));
    }

    @Test
    @WithMockUser(username = ADMIN_UUID, roles = "ADMIN")
    void updateInvokesServiceAndRedirects() throws Exception {
        mockMvc.perform(post("/admin/config/rec.lambda_blend").param("value", "0.5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/config"));
        verify(configService)
                .update("rec.lambda_blend", "0.5", UserId.of(UUID.fromString(ADMIN_UUID)));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentForbiddenFromView() throws Exception {
        mockMvc.perform(get("/admin/config")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "INSTRUCTOR")
    void instructorForbiddenFromUpdate() throws Exception {
        mockMvc.perform(post("/admin/config/rec.lambda_blend").param("value", "0.5"))
                .andExpect(status().isForbidden());
        verify(configService, never()).update(any(), any(), any());
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
