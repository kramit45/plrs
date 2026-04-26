package com.plrs.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.plrs.application.security.TokenService;
import com.plrs.web.health.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Comprehensive header audit per §7. Hits a benign endpoint and
 * verifies every security-relevant response header is set with the
 * expected value. The CSP check explicitly asserts no
 * {@code 'unsafe-inline'} appears in the {@code script-src}
 * directive — drift here is the most common security regression.
 *
 * <p>Traces to: §7 (security controls).
 */
@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, PasswordEncoderBridge.class})
class SecurityHeadersAuditTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private TokenService tokenService;

    @Test
    void healthResponseCarriesFullSecurityHeaderSet() throws Exception {
        // Spring Security only emits HSTS over HTTPS — mark the mock
        // request secure so the header is included in the response.
        MvcResult result =
                mockMvc.perform(get("/health").secure(true)).andReturn();

        assertThat(result.getResponse().getHeader("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(result.getResponse().getHeader("Strict-Transport-Security"))
                .contains("max-age=")
                .contains("includeSubDomains");
        assertThat(result.getResponse().getHeader("Referrer-Policy"))
                .isEqualTo("strict-origin-when-cross-origin");
        assertThat(result.getResponse().getHeader("Permissions-Policy"))
                .contains("geolocation=()")
                .contains("camera=()");
        assertThat(result.getResponse().getHeader("X-Robots-Tag"))
                .as("demo deployment must not be indexed by crawlers")
                .isEqualTo("noindex, nofollow");

        String csp = result.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull();
        // Critical drift check: script-src must NEVER include 'unsafe-inline'.
        // Find the script-src directive and ensure 'unsafe-inline' is absent.
        String scriptSrc = extractDirective(csp, "script-src");
        assertThat(scriptSrc)
                .as("script-src must not allow unsafe-inline")
                .doesNotContain("'unsafe-inline'");
        assertThat(csp).contains("frame-ancestors 'none'");
        assertThat(csp).contains("default-src 'self'");
    }

    /** Returns the value portion of a single CSP directive, or empty when absent. */
    private static String extractDirective(String csp, String directive) {
        for (String part : csp.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(directive + " ") || trimmed.equals(directive)) {
                return trimmed.substring(directive.length()).trim();
            }
        }
        return "";
    }
}
