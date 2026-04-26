package com.plrs.application.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.application.security.PasswordEncoder;
import com.plrs.application.security.RefreshTokenStore;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.user.BCryptHash;
import com.plrs.domain.user.Email;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PasswordResetUseCasesTest {

    private static final Instant T0 = Instant.parse("2026-04-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final String VALID_HASH =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Mock private UserRepository users;
    @Mock private PasswordEncoder encoder;
    @Mock private RefreshTokenStore refreshTokens;

    private User stored() {
        return User.rehydrate(
                UserId.of(UUID.randomUUID()),
                Email.of("kumar@example.com"),
                BCryptHash.of(VALID_HASH),
                Set.of(Role.STUDENT),
                AuditFields.initial("system", CLOCK));
    }

    // ---- Request ----

    @Test
    void requestReturnsTokenForKnownEmail() {
        User user = stored();
        when(users.findByEmail(any())).thenReturn(Optional.of(user));

        Optional<String> token =
                new RequestPasswordResetUseCase(users, CLOCK).handle("kumar@example.com");

        assertThat(token).isPresent();
        ArgumentCaptor<Instant> exp = ArgumentCaptor.forClass(Instant.class);
        verify(users).setResetToken(eq(user.id()), eq(token.get()), exp.capture());
        assertThat(exp.getValue())
                .isEqualTo(T0.plus(RequestPasswordResetUseCase.TOKEN_TTL));
    }

    @Test
    void requestReturnsEmptyForUnknownEmail() {
        when(users.findByEmail(any())).thenReturn(Optional.empty());

        Optional<String> token =
                new RequestPasswordResetUseCase(users, CLOCK).handle("ghost@example.com");

        assertThat(token).isEmpty();
        verify(users, never()).setResetToken(any(), anyString(), any());
    }

    @Test
    void requestSilentlyDropsMalformedEmail() {
        Optional<String> token =
                new RequestPasswordResetUseCase(users, CLOCK).handle("not-an-email");
        assertThat(token).isEmpty();
        verify(users, never()).findByEmail(any());
    }

    // ---- Confirm ----

    @Test
    void confirmHappyPath() {
        User user = stored();
        when(users.findByResetToken("abc")).thenReturn(Optional.of(user));
        when(users.getResetExpiresAt(user.id())).thenReturn(Optional.of(T0.plusSeconds(60)));
        when(encoder.encode("NewPassword01")).thenReturn(BCryptHash.of(VALID_HASH));

        new ConfirmPasswordResetUseCase(users, encoder, refreshTokens, CLOCK)
                .handle("abc", "NewPassword01");

        verify(users).updatePasswordHash(user.id(), VALID_HASH);
        verify(users).clearResetToken(user.id());
        verify(refreshTokens).revokeAllForUser(user.id());
    }

    @Test
    void confirmRejectsExpiredToken() {
        User user = stored();
        when(users.findByResetToken("expired")).thenReturn(Optional.of(user));
        when(users.getResetExpiresAt(user.id())).thenReturn(Optional.of(T0.minusSeconds(1)));

        assertThatThrownBy(
                        () ->
                                new ConfirmPasswordResetUseCase(
                                                users, encoder, refreshTokens, CLOCK)
                                        .handle("expired", "NewPassword01"))
                .isInstanceOf(InvalidResetTokenException.class);

        verify(users, never()).updatePasswordHash(any(), anyString());
        verify(refreshTokens, never()).revokeAllForUser(any());
    }

    @Test
    void confirmRejectsUnknownToken() {
        when(users.findByResetToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                new ConfirmPasswordResetUseCase(
                                                users, encoder, refreshTokens, CLOCK)
                                        .handle("nope", "NewPassword01"))
                .isInstanceOf(InvalidResetTokenException.class);
    }

    @Test
    void confirmRejectsBlankToken() {
        assertThatThrownBy(
                        () ->
                                new ConfirmPasswordResetUseCase(
                                                users, encoder, refreshTokens, CLOCK)
                                        .handle("", "NewPassword01"))
                .isInstanceOf(InvalidResetTokenException.class);
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
