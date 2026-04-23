package com.plrs.web.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that correlates every HTTP request with a stable id. The id
 * is either propagated from the inbound {@value #HEADER} header (so callers
 * and upstream proxies can stitch logs across systems) or generated as a
 * fresh UUID when absent. The id is:
 *
 * <ul>
 *   <li>placed into the SLF4J {@link MDC} under {@code requestId} so every
 *       log statement emitted while the request is in flight carries it
 *       (LogstashEncoder pulls the MDC automatically), and
 *   <li>echoed on the response under the same header so clients can reference
 *       it in bug reports.
 * </ul>
 *
 * <p>Ordered at {@link Ordered#HIGHEST_PRECEDENCE} {@code + 1} so the id is
 * present for every downstream filter (security, auth, exception handling)
 * and the MDC is always cleared on the way out via the {@code finally}
 * block, preventing id leakage onto recycled Tomcat threads.
 *
 * <p>Traces to: §7 (audit traceability).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String requestId = (inbound == null || inbound.isBlank()) ? UUID.randomUUID().toString() : inbound;

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
