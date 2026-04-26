package com.plrs.web.admin;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.audit.AuditEntry;
import com.plrs.application.audit.AuditQueryService;
import com.plrs.application.audit.AuditQueryService.AuditPage;
import com.plrs.application.security.TokenService;
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

@WebMvcTest(controllers = AdminAuditController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AdminAuditControllerTest.MethodSecurityTestConfig.class)
class AdminAuditControllerTest {

    private static final String ADMIN_UUID = "11111111-2222-3333-4444-555555555555";

    @Autowired private MockMvc mockMvc;

    @MockBean private AuditQueryService auditService;
    @MockBean private TokenService tokenService;

    private AuditPage samplePage() {
        return new AuditPage(
                List.of(
                        new AuditEntry(
                                42L,
                                Instant.parse("2026-04-26T12:00:00Z"),
                                Optional.of(UUID.fromString(ADMIN_UUID)),
                                "USER_REGISTERED",
                                Optional.of("user"),
                                Optional.of("99"),
                                Optional.of("{\"k\":\"v\"}"))),
                0,
                50,
                1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminSeesAuditTable() throws Exception {
        when(auditService.search(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(samplePage());

        mockMvc.perform(get("/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("USER_REGISTERED")))
                .andExpect(content().string(containsString("audit-table")));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentForbidden() throws Exception {
        mockMvc.perform(get("/admin/audit")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "INSTRUCTOR")
    void instructorForbidden() throws Exception {
        mockMvc.perform(get("/admin/audit")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void emptyResultRendersEmptyState() throws Exception {
        when(auditService.search(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AuditPage(List.of(), 0, 50, 0));

        mockMvc.perform(get("/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No matching audit rows")));
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
