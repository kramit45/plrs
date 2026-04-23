package com.plrs.web.user;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link MeController}. Asserts the two contractual
 * behaviours: the default Spring Security filter chain rejects an
 * anonymous request with 401, and an authenticated request sees its
 * principal and roles echoed back.
 */
@WebMvcTest(MeController.class)
class MeControllerTest {

    @Autowired private MockMvc mockMvc;

    // SecurityConfig lands on the slice classpath via user @Configuration scan
    // and needs TokenService available to instantiate JwtAuthenticationFilter.
    @MockBean private TokenService tokenService;

    @Test
    void anonymousRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedRequestReturnsPrincipalAndRoles() throws Exception {
        String userId = "11111111-2222-3333-4444-555555555555";

        mockMvc.perform(get("/api/me").with(user(userId).authorities(() -> "ROLE_STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_STUDENT"));
    }
}
