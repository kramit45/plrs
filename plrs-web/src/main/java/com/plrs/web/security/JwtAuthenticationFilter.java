package com.plrs.web.security;

import com.plrs.application.security.InvalidTokenException;
import com.plrs.application.security.TokenClaims;
import com.plrs.application.security.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that extracts a Bearer access token from the
 * {@code Authorization} header, verifies it via {@link TokenService}, and
 * populates {@link SecurityContextHolder} with the resulting principal
 * and {@code ROLE_}-prefixed authorities.
 *
 * <p>Three outcomes:
 *
 * <ul>
 *   <li>No {@code Authorization} header, or not a {@code Bearer} scheme —
 *       the filter is a no-op; downstream {@code authorizeHttpRequests}
 *       rules decide whether the request proceeds or 401s.
 *   <li>Bearer token that verifies — the {@link SecurityContext} is set
 *       with the user id as principal and one {@link SimpleGrantedAuthority}
 *       per role (prefixed {@code ROLE_} so Spring's {@code hasRole(...)}
 *       shortcuts work).
 *   <li>Bearer token that fails verification — the context is cleared
 *       and the request continues with no authentication, letting the
 *       authorize rules produce the usual 401. We deliberately do not
 *       short-circuit here; the downstream entry point formats the
 *       response and picks up {@code requestId} from MDC.
 * </ul>
 *
 * <p>Traces to: §7 (JWT bearer-token authentication for the API chain).
 */
@Component
public final class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final TokenService tokenService;

    public JwtAuthenticationFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length());
        try {
            TokenClaims claims = tokenService.verifyAccess(token);
            List<SimpleGrantedAuthority> authorities =
                    claims.roles().stream()
                            .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role.name()))
                            .toList();
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            claims.subject().value().toString(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (InvalidTokenException e) {
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }
}
