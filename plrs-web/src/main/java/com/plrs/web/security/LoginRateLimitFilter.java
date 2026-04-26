package com.plrs.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP rate-limit guard for {@code POST /api/auth/login}. Reads
 * {@code X-Forwarded-For} (first hop) so the limiter sees the real
 * client behind a reverse proxy; falls back to
 * {@link HttpServletRequest#getRemoteAddr()} when the header is
 * absent.
 *
 * <p>On exceeded the filter responds {@code 429 Too Many Requests}
 * with a {@code Retry-After} header derived from
 * {@link IpRateLimiter#retryAfterSeconds(String)} and a
 * {@code application/problem+json} body — same shape as the existing
 * per-user limiter. Spring Security never sees the request when the
 * limit is exceeded.
 *
 * <p>Traces to: NFR-31 extended.
 */
/**
 * Not a {@code @Component} — registered as a manual {@code @Bean} in
 * {@link SecurityConfig} so {@code @WebMvcTest} slice tests don't have to
 * wire {@link IpRateLimiter} themselves. The full app context picks it up
 * fine because SecurityConfig is loaded.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    static final String LOGIN_PATH = "/api/auth/login";

    private final IpRateLimiter limiter;
    private final ObjectMapper objectMapper;

    public LoginRateLimitFilter(IpRateLimiter limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!"POST".equals(request.getMethod())
                || !LOGIN_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String ip = clientIp(request);
        if (limiter.tryAcquire(ip)) {
            chain.doFilter(request, response);
            return;
        }

        long retry = limiter.retryAfterSeconds(ip);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retry));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://plrs.example/problems/login-rate-limited");
        body.put("title", "Too Many Requests");
        body.put("status", 429);
        body.put(
                "detail",
                "Too many login attempts from this IP. Try again in " + retry + " seconds.");
        body.put("retryAfterSeconds", retry);
        objectMapper.writeValue(response.getWriter(), body);
    }

    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
