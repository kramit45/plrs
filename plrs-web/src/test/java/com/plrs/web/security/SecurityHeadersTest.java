package com.plrs.web.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.security.RefreshTokenStore;
import com.plrs.application.security.TokenService;
import com.plrs.application.user.LoginUseCase;
import com.plrs.application.user.LogoutUseCase;
import com.plrs.application.user.RegisterUserUseCase;
import com.plrs.domain.user.UserRepository;
import com.plrs.web.auth.AuthController;
import com.plrs.web.auth.AuthFormController;
import com.plrs.web.common.GlobalExceptionHandler;
import com.plrs.web.health.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test covering §7 security-controls hardening: response headers,
 * CORS preflight handling, and CSRF enforcement on the web chain.
 *
 * <p>Same slice pattern as {@code SecurityConfigTest} — {@code @WebMvcTest}
 * plus explicit imports of the security wiring — so the full filter chain
 * runs without dragging in JPA/Redis auto-configuration.
 */
@WebMvcTest(
        controllers = {AuthController.class, AuthFormController.class, HealthController.class},
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = GlobalExceptionHandler.class))
@Import({
    SecurityConfig.class,
    PasswordEncoderBridge.class,
    JwtAuthenticationFilter.class,
    CorsConfig.class
})
@TestPropertySource(properties = "plrs.cors.allowed-origins=http://localhost:8080")
class SecurityHeadersTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private RegisterUserUseCase registerUseCase;
    @MockBean private LoginUseCase loginUseCase;
    @MockBean private LogoutUseCase logoutUseCase;
    @MockBean private UserRepository userRepository;
    @MockBean private TokenService tokenService;
    @MockBean private RefreshTokenStore refreshTokenStore;

    @Test
    void responseCarriesTheFullSecurityHeaderSet() throws Exception {
        // Spring Security's HSTS writer only emits on secure requests, so
        // the test must flag the request as HTTPS — otherwise we'd miss the
        // header that prod clients would actually see.
        mockMvc.perform(get("/health").secure(true))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Strict-Transport-Security",
                                        containsString("max-age=31536000")))
                .andExpect(
                        header().string(
                                        "Strict-Transport-Security",
                                        containsString("includeSubDomains")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(
                        header().string(
                                        "Referrer-Policy",
                                        "strict-origin-when-cross-origin"))
                .andExpect(
                        header().string("Permissions-Policy", containsString("geolocation=()")))
                .andExpect(
                        header().string(
                                        "Content-Security-Policy",
                                        containsString("default-src 'self'")))
                .andExpect(
                        header().string(
                                        "Content-Security-Policy",
                                        containsString("frame-ancestors 'none'")));
    }

    @Test
    void corsPreflightAllowsConfiguredOrigin() throws Exception {
        mockMvc.perform(
                        options("/api/auth/register")
                                .header("Origin", "http://localhost:8080")
                                .header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("Access-Control-Allow-Origin", "http://localhost:8080"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
    }

    @Test
    void corsPreflightRejectsUnlistedOrigin() throws Exception {
        mockMvc.perform(
                        options("/api/auth/register")
                                .header("Origin", "http://evil.example")
                                .header("Access-Control-Request-Method", "POST"))
                .andExpect(
                        result -> {
                            int s = result.getResponse().getStatus();
                            String acao =
                                    result.getResponse().getHeader("Access-Control-Allow-Origin");
                            if (s < 400 && "http://evil.example".equals(acao)) {
                                throw new AssertionError(
                                        "expected either non-2xx or missing ACAO for unlisted"
                                                + " origin; got status "
                                                + s
                                                + " and ACAO "
                                                + acao);
                            }
                        });
    }

    @Test
    void postRegisterOnWebChainWithoutCsrfTokenReturns403() throws Exception {
        mockMvc.perform(
                        post("/register")
                                .param("email", "kumar@example.com")
                                .param("password", "Password01"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postRegisterOnWebChainWithCsrfTokenProceeds() throws Exception {
        mockMvc.perform(
                        post("/register")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.csrf())
                                .param("email", "kumar@example.com")
                                .param("password", "Password01"))
                .andExpect(
                        result -> {
                            int s = result.getResponse().getStatus();
                            // Happy path with a valid CSRF token is either 302
                            // (redirect to /login?registered when the use case
                            // returns cleanly) or 200 (form re-render with a
                            // use-case-thrown error); the security contract we
                            // care about here is that it is not 403.
                            if (s == 403) {
                                throw new AssertionError(
                                        "CSRF token was supplied but request was still forbidden");
                            }
                        });
    }
}
