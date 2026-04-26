package com.plrs.web.admin;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.admin.KpiService;
import com.plrs.application.admin.KpiService.EvalMetric;
import com.plrs.application.admin.KpiService.KpiSnapshot;
import com.plrs.application.admin.KpiService.ScalarKpi;
import com.plrs.application.admin.KpiService.WeeklyRating;
import com.plrs.application.security.TokenService;
import com.plrs.infrastructure.admin.RefreshKpiViewsJob;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

@WebMvcTest(controllers = AdminDashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AdminDashboardControllerTest.MethodSecurityTestConfig.class)
class AdminDashboardControllerTest {

    private static final String ADMIN_UUID = "11111111-2222-3333-4444-555555555555";
    private static final Instant NOW = Instant.parse("2026-04-26T12:00:00Z");

    @Autowired private MockMvc mockMvc;

    @MockBean private KpiService kpiService;
    @MockBean private RefreshKpiViewsJob refreshJob;
    @MockBean private TokenService tokenService;

    private KpiSnapshot snapshot() {
        return new KpiSnapshot(
                new ScalarKpi(new BigDecimal("0.65"), NOW),
                new ScalarKpi(new BigDecimal("0.18"), NOW),
                new ScalarKpi(new BigDecimal("0.22"), NOW),
                List.of(),
                List.of(new WeeklyRating("2026-17", new BigDecimal("4.30"), 8)),
                List.of(
                        new EvalMetric(
                                "cf_v1",
                                new BigDecimal("0.4500"),
                                new BigDecimal("0.5500"),
                                new BigDecimal("0.6500"),
                                NOW)));
    }

    @Test
    @WithMockUser(username = ADMIN_UUID, roles = "ADMIN")
    void adminSeesAllSixKpiTiles() throws Exception {
        when(kpiService.snapshot()).thenReturn(snapshot());

        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("kpi-coverage")))
                .andExpect(content().string(containsString("kpi-ctr")))
                .andExpect(content().string(containsString("kpi-completion-avg")))
                .andExpect(content().string(containsString("kpi-cold-exposure")))
                .andExpect(content().string(containsString("kpi-rating")))
                .andExpect(content().string(containsString("kpi-precision")))
                .andExpect(content().string(containsString("0.4500")));
    }

    @Test
    @WithMockUser(username = ADMIN_UUID, roles = "STUDENT")
    void studentForbiddenFromDashboard() throws Exception {
        mockMvc.perform(get("/admin/dashboard")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN_UUID, roles = "ADMIN")
    void refreshTriggersJob() throws Exception {
        mockMvc.perform(post("/api/admin/kpi/refresh"))
                .andExpect(status().isNoContent());
        verify(refreshJob).refreshNow();
    }

    @Test
    @WithMockUser(username = ADMIN_UUID, roles = "STUDENT")
    void studentRefreshForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/kpi/refresh"))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
