package com.plrs.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.plrs.application.security.InvalidTokenException;
import com.plrs.application.security.TokenClaims;
import com.plrs.application.security.TokenService;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.UserId;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link JwtAuthenticationFilter}. Invokes the filter
 * directly rather than through MockMvc so we can assert on
 * {@link SecurityContextHolder} before anything else in Spring's request
 * lifecycle clears it.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private TokenService tokenService;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(tokenService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingAuthorizationHeaderProceedsWithoutSettingContext() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(tokenService);
    }

    @Test
    void nonBearerHeaderProceedsWithoutSettingContext() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(tokenService);
    }

    @Test
    void validBearerTokenPopulatesSecurityContextWithRolePrefixedAuthorities() throws Exception {
        UserId userId = UserId.of(UUID.randomUUID());
        TokenClaims claims =
                new TokenClaims(
                        userId,
                        Set.of(Role.STUDENT, Role.INSTRUCTOR),
                        null,
                        Instant.parse("2026-04-23T12:00:00Z"));
        when(tokenService.verifyAccess("valid.jwt.token")).thenReturn(claims);
        request.addHeader("Authorization", "Bearer valid.jwt.token");

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo(userId.value().toString());
        assertThat(auth.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactlyInAnyOrder("ROLE_STUDENT", "ROLE_INSTRUCTOR");
        verify(chain).doFilter(request, response);
    }

    @Test
    void invalidBearerTokenClearsContextAndProceeds() throws Exception {
        when(tokenService.verifyAccess("bad.token"))
                .thenThrow(new InvalidTokenException("bad signature"));
        request.addHeader("Authorization", "Bearer bad.token");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void expiredBearerTokenClearsContextAndProceeds() throws Exception {
        when(tokenService.verifyAccess("expired.token"))
                .thenThrow(new InvalidTokenException("token expired"));
        request.addHeader("Authorization", "Bearer expired.token");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void emptyBearerTokenStillCallsVerifyAndProceedsOnFailure() throws Exception {
        when(tokenService.verifyAccess("")).thenThrow(new InvalidTokenException("empty"));
        request.addHeader("Authorization", "Bearer ");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verify(tokenService, never()).verifyRefresh(any());
    }
}
