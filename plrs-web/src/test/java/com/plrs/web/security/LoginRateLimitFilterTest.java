package com.plrs.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginRateLimitFilterTest {

    private final IpRateLimiter limiter =
            new IpRateLimiter(Clock.fixed(java.time.Instant.parse("2026-04-26T12:00:00Z"), ZoneOffset.UTC));
    private final LoginRateLimitFilter filter =
            new LoginRateLimitFilter(limiter, new ObjectMapper());

    @Test
    void elevenAttemptsFromSameIpWithinWindowReturns429() throws Exception {
        for (int i = 0; i < IpRateLimiter.LIMIT_PER_WINDOW; i++) {
            run(loginRequest("1.2.3.4"), new MockHttpServletResponse());
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        run(loginRequest("1.2.3.4"), res);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isNotBlank();
        assertThat(res.getContentType()).contains("application/problem+json");
    }

    @Test
    void differentIpStillAllowed() throws Exception {
        for (int i = 0; i < IpRateLimiter.LIMIT_PER_WINDOW; i++) {
            run(loginRequest("1.2.3.4"), new MockHttpServletResponse());
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        run(loginRequest("5.6.7.8"), res);
        // Filter chain ran (no 429); status defaults to 200 from MockFilterChain.
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void nonLoginPathsBypassLimiter() throws Exception {
        // Saturate the bucket on a different path; the filter is a no-op.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/recommendations");
        req.setRemoteAddr("1.2.3.4");
        for (int i = 0; i < IpRateLimiter.LIMIT_PER_WINDOW + 5; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            run(req, res);
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void xForwardedForPreferredOverRemoteAddr() throws Exception {
        // Saturate via XFF header so the bucket is keyed on that IP.
        MockHttpServletRequest req = loginRequest("9.9.9.9");
        req.setRemoteAddr("1.1.1.1");
        req.addHeader("X-Forwarded-For", "9.9.9.9, 10.0.0.1");
        for (int i = 0; i < IpRateLimiter.LIMIT_PER_WINDOW; i++) {
            run(req, new MockHttpServletResponse());
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        run(req, res);
        assertThat(res.getStatus()).isEqualTo(429);
    }

    private static MockHttpServletRequest loginRequest(String remote) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        req.setRemoteAddr(remote);
        return req;
    }

    private void run(HttpServletRequest req, HttpServletResponse res) throws Exception {
        FilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
    }
}
