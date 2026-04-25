package com.plrs.web.dashboard;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.dashboard.WeeklyActivityService;
import com.plrs.application.dashboard.WeeklyBucket;
import com.plrs.application.security.TokenService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = DashboardApiController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = com.plrs.web.common.GlobalExceptionHandler.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(DashboardApiControllerTest.MethodSecurityTestConfig.class)
class DashboardApiControllerTest {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";

    @Autowired private MockMvc mockMvc;

    @MockBean private WeeklyActivityService service;
    @MockBean private TokenService tokenService;

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void getActivityWeeklyAsStudent200() throws Exception {
        when(service.last8Weeks(org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        List.of(
                                new WeeklyBucket("2026-10", 0),
                                new WeeklyBucket("2026-11", 3),
                                new WeeklyBucket("2026-12", 0),
                                new WeeklyBucket("2026-13", 1),
                                new WeeklyBucket("2026-14", 0),
                                new WeeklyBucket("2026-15", 5),
                                new WeeklyBucket("2026-16", 2),
                                new WeeklyBucket("2026-17", 4)));

        mockMvc.perform(get("/web-api/me/activity-weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].isoYearWeek").value("2026-10"))
                .andExpect(jsonPath("$[7].isoYearWeek").value("2026-17"))
                .andExpect(jsonPath("$[7].count").value(4));
    }

    @Test
    void getActivityWeeklyUnauthenticatedDenied() throws Exception {
        mockMvc.perform(get("/web-api/me/activity-weekly"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "INSTRUCTOR")
    void getActivityWeeklyAsInstructor403() throws Exception {
        mockMvc.perform(get("/web-api/me/activity-weekly"))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
