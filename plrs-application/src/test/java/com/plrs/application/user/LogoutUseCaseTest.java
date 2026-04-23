package com.plrs.application.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.application.security.InvalidTokenException;
import com.plrs.application.security.RefreshTokenStore;
import com.plrs.application.security.TokenClaims;
import com.plrs.application.security.TokenService;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LogoutUseCaseTest {

    private static final String REFRESH_TOKEN = "refresh.jwt.token";
    private static final String JTI = "abc-123";

    @Mock private TokenService tokenService;
    @Mock private RefreshTokenStore refreshStore;

    private LogoutUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new LogoutUseCase(tokenService, refreshStore);
    }

    private static TokenClaims stubClaims() {
        return new TokenClaims(
                UserId.of(UUID.randomUUID()),
                Set.of(),
                JTI,
                Instant.parse("2026-05-23T10:00:00Z"));
    }

    @Test
    void happyPathRevokesJti() {
        when(tokenService.verifyRefresh(REFRESH_TOKEN)).thenReturn(stubClaims());

        useCase.handle(new LogoutCommand(REFRESH_TOKEN));

        verify(refreshStore).revoke(JTI);
    }

    @Test
    void nullRefreshTokenThrowsInvalidTokenExceptionAndSkipsDownstream() {
        assertThatThrownBy(() -> useCase.handle(new LogoutCommand(null)))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("required");

        verify(tokenService, never()).verifyRefresh(any());
        verify(refreshStore, never()).revoke(any());
    }

    @Test
    void blankRefreshTokenThrowsInvalidTokenException() {
        assertThatThrownBy(() -> useCase.handle(new LogoutCommand("   ")))
                .isInstanceOf(InvalidTokenException.class);

        verify(tokenService, never()).verifyRefresh(any());
        verify(refreshStore, never()).revoke(any());
    }

    @Test
    void invalidRefreshTokenPropagatesAndSkipsRevoke() {
        when(tokenService.verifyRefresh(REFRESH_TOKEN))
                .thenThrow(new InvalidTokenException("bad signature"));

        assertThatThrownBy(() -> useCase.handle(new LogoutCommand(REFRESH_TOKEN)))
                .isInstanceOf(InvalidTokenException.class);

        verify(refreshStore, never()).revoke(any());
    }

    @Test
    void expiredRefreshTokenPropagatesAndSkipsRevoke() {
        when(tokenService.verifyRefresh(REFRESH_TOKEN))
                .thenThrow(new InvalidTokenException("token expired"));

        assertThatThrownBy(() -> useCase.handle(new LogoutCommand(REFRESH_TOKEN)))
                .isInstanceOf(InvalidTokenException.class);

        verify(refreshStore, never()).revoke(any());
    }

    @Test
    void alreadyRevokedRefreshTokenStillSucceedsAndCallsRevokeAgain() {
        // Verify still passes — signature is valid even if Redis has already
        // dropped the jti. The store's revoke() is idempotent, so logging
        // out twice from the same client is a silent no-op on the second call.
        when(tokenService.verifyRefresh(REFRESH_TOKEN)).thenReturn(stubClaims());

        useCase.handle(new LogoutCommand(REFRESH_TOKEN));
        useCase.handle(new LogoutCommand(REFRESH_TOKEN));

        verify(refreshStore, org.mockito.Mockito.times(2)).revoke(JTI);
    }
}
