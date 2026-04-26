package com.plrs.web.admin;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.security.TokenService;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
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

@WebMvcTest(controllers = AdminUserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AdminUserControllerTest.MethodSecurityTestConfig.class)
class AdminUserControllerTest {

    private static final String ADMIN_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String TARGET_UUID = "99999999-8888-7777-6666-555555555555";

    @Autowired private MockMvc mockMvc;

    @MockBean private UserRepository userRepository;
    @MockBean private TokenService tokenService;

    @Test
    @WithMockUser(username = ADMIN_UUID, roles = "ADMIN")
    void adminUnlockClearsTheLock() throws Exception {
        mockMvc.perform(post("/api/admin/users/{id}/unlock", TARGET_UUID))
                .andExpect(status().isNoContent());
        verify(userRepository).unlock(UserId.of(java.util.UUID.fromString(TARGET_UUID)));
    }

    @Test
    @WithMockUser(username = ADMIN_UUID, roles = "STUDENT")
    void studentForbiddenFromUnlock() throws Exception {
        mockMvc.perform(post("/api/admin/users/{id}/unlock", TARGET_UUID))
                .andExpect(status().isForbidden());
        verify(userRepository, never()).unlock(any());
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
