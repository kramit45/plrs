package com.plrs.application.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.application.security.IssuedTokens;
import com.plrs.application.security.PasswordEncoder;
import com.plrs.application.security.RefreshTokenStore;
import com.plrs.application.security.TokenService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoginUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-04-23T10:00:00Z");
    private static final Instant ACCESS_EXP = T0.plusSeconds(3600 * 2);
    private static final Instant REFRESH_EXP = T0.plusSeconds(3600 * 24 * 30);
    private static final String STORED_HASH =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Mock private UserRepository users;
    @Mock private PasswordEncoder encoder;
    @Mock private TokenService tokenService;
    @Mock private RefreshTokenStore refreshStore;

    private LoginUseCase useCase;

    private static final Clock FIXED = Clock.fixed(T0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        useCase = new LoginUseCase(users, encoder, tokenService, refreshStore, FIXED);
    }

    private static User storedUser(String email) {
        return User.rehydrate(
                UserId.of(UUID.randomUUID()),
                Email.of(email),
                BCryptHash.of(STORED_HASH),
                Set.of(Role.STUDENT),
                AuditFields.initial("system", T0));
    }

    private static IssuedTokens stubTokens() {
        return new IssuedTokens(
                "access.jwt", "refresh.jwt", "refresh-jti", ACCESS_EXP, REFRESH_EXP);
    }

    @Test
    void lockedAccountThrowsBeforeBcrypt() {
        User user = storedUser("kumar@example.com");
        when(users.findByEmail(any())).thenReturn(Optional.of(user));
        when(users.getLockedUntil(user.id()))
                .thenReturn(Optional.of(T0.plusSeconds(600)));

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new LoginCommand("kumar@example.com", "Password01")))
                .isInstanceOf(AccountLockedException.class);
        verify(encoder, never()).matches(anyString(), any());
        verify(refreshStore, never()).store(anyString(), any(), any());
    }

    @Test
    void successResetsFailureCounter() {
        User user = storedUser("kumar@example.com");
        when(users.findByEmail(any())).thenReturn(Optional.of(user));
        when(users.getLockedUntil(user.id())).thenReturn(Optional.empty());
        when(encoder.matches(eq("Password01"), any())).thenReturn(true);
        when(tokenService.issue(user.id(), user.roles())).thenReturn(stubTokens());

        useCase.handle(new LoginCommand("kumar@example.com", "Password01"));

        verify(users).recordLoginSuccess(user.id());
        verify(users, never()).recordLoginFailure(any(), any());
    }

    @Test
    void failedLoginRecordsFailureForKnownUser() {
        User user = storedUser("kumar@example.com");
        when(users.findByEmail(any())).thenReturn(Optional.of(user));
        when(users.getLockedUntil(user.id())).thenReturn(Optional.empty());
        when(encoder.matches(anyString(), any())).thenReturn(false);

        assertThatThrownBy(
                        () -> useCase.handle(new LoginCommand("kumar@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(users).recordLoginFailure(user.id(), T0);
    }

    @Test
    void unknownEmailDoesNotRecordFailure() {
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(encoder.matches(anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.handle(new LoginCommand("ghost@x.com", "pw")))
                .isInstanceOf(InvalidCredentialsException.class);
        // Don't let attackers lock arbitrary unknown emails.
        verify(users, never()).recordLoginFailure(any(), any());
    }

    @Test
    void happyPathIssuesTokensAndStoresRefreshJti() {
        User user = storedUser("kumar@example.com");
        when(users.findByEmail(any())).thenReturn(Optional.of(user));
        when(users.getLockedUntil(user.id())).thenReturn(Optional.empty());
        when(encoder.matches(eq("Password01"), any())).thenReturn(true);
        when(tokenService.issue(user.id(), user.roles())).thenReturn(stubTokens());

        LoginResult result = useCase.handle(new LoginCommand("kumar@example.com", "Password01"));

        verify(refreshStore).store("refresh-jti", user.id(), REFRESH_EXP);
        assertThat(result.userId()).isEqualTo(user.id());
        assertThat(result.email()).isEqualTo(user.email());
        assertThat(result.roles()).containsExactly(Role.STUDENT);
        assertThat(result.accessToken()).isEqualTo("access.jwt");
        assertThat(result.refreshToken()).isEqualTo("refresh.jwt");
        assertThat(result.accessExpiresAt()).isEqualTo(ACCESS_EXP);
        assertThat(result.refreshExpiresAt()).isEqualTo(REFRESH_EXP);
    }

    @Test
    void wrongPasswordThrowsInvalidCredentialsAndDoesNotIssueTokens() {
        User user = storedUser("kumar@example.com");
        when(users.findByEmail(any())).thenReturn(Optional.of(user));
        when(encoder.matches(anyString(), any())).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new LoginCommand("kumar@example.com", "wrong1Pass")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password")
                .hasMessageNotContaining("kumar");

        verify(tokenService, never()).issue(any(), any());
        verify(refreshStore, never()).store(any(), any(), any());
    }

    @Test
    void unknownEmailThrowsAndStillRunsBcryptMatchOnDummyHash() {
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(encoder.matches(anyString(), any())).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new LoginCommand("ghost@example.com", "Password01")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageNotContaining("ghost");

        ArgumentCaptor<BCryptHash> hashCaptor = ArgumentCaptor.forClass(BCryptHash.class);
        verify(encoder).matches(eq("Password01"), hashCaptor.capture());
        assertThat(hashCaptor.getValue().value()).isEqualTo(LoginUseCase.DUMMY_HASH_VALUE);
        verify(tokenService, never()).issue(any(), any());
    }

    @Test
    void invalidEmailFormatRunsDummyMatchAndSkipsRepository() {
        assertThatThrownBy(
                        () -> useCase.handle(new LoginCommand("not-an-email", "Password01")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageNotContaining("not-an-email");

        ArgumentCaptor<BCryptHash> hashCaptor = ArgumentCaptor.forClass(BCryptHash.class);
        verify(encoder).matches(eq("Password01"), hashCaptor.capture());
        assertThat(hashCaptor.getValue().value()).isEqualTo(LoginUseCase.DUMMY_HASH_VALUE);
        verify(users, never()).findByEmail(any());
    }

    @Test
    void nullRawPasswordThrowsInvalidCredentialsWithoutNpe() {
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(encoder.matches(anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.handle(new LoginCommand("kumar@example.com", null)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(encoder).matches(eq(""), any());
    }

    @Test
    void emailNormalisationAppliedBeforeLookup() {
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(encoder.matches(anyString(), any())).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new LoginCommand(
                                                "  User@Example.COM ", "Password01")))
                .isInstanceOf(InvalidCredentialsException.class);

        ArgumentCaptor<Email> emailCaptor = ArgumentCaptor.forClass(Email.class);
        verify(users).findByEmail(emailCaptor.capture());
        assertThat(emailCaptor.getValue().value()).isEqualTo("user@example.com");
    }
}
