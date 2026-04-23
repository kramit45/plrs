package com.plrs.application.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.application.security.PasswordEncoder;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.user.BCryptHash;
import com.plrs.domain.user.Email;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegisterUserUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-04-23T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final String VALID_EMAIL = "kumar@example.com";
    private static final String VALID_PASSWORD = "Password01";
    private static final BCryptHash STUB_HASH =
            BCryptHash.of("$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1");

    @Mock private UserRepository users;
    @Mock private PasswordEncoder encoder;

    private RegisterUserUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RegisterUserUseCase(users, encoder, CLOCK);
    }

    @Test
    void happyPathPersistsUserAndReturnsId() {
        when(users.existsByEmail(any())).thenReturn(false);
        when(encoder.encode(VALID_PASSWORD)).thenReturn(STUB_HASH);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var id = useCase.handle(new RegisterUserCommand(VALID_EMAIL, VALID_PASSWORD, "self"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.email().value()).isEqualTo(VALID_EMAIL);
        assertThat(saved.passwordHash()).isEqualTo(STUB_HASH);
        assertThat(saved.roles()).containsExactly(Role.STUDENT);
        assertThat(saved.audit().createdAt()).isEqualTo(T0);
        assertThat(saved.audit().createdBy()).isEqualTo("self");
        assertThat(id).isEqualTo(saved.id());
    }

    @Test
    void duplicateEmailThrowsAndShortCircuitsBeforeEncoding() {
        when(users.existsByEmail(any())).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new RegisterUserCommand(VALID_EMAIL, VALID_PASSWORD, "self")))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessageContaining(VALID_EMAIL);

        verify(encoder, never()).encode(any());
        verify(users, never()).save(any());
    }

    @Test
    void invalidEmailFormatThrowsDomainValidationExceptionBeforeRepositoryIsTouched() {
        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new RegisterUserCommand(
                                                "not-an-email", VALID_PASSWORD, "self")))
                .isInstanceOf(DomainValidationException.class);

        verify(users, never()).existsByEmail(any());
        verify(encoder, never()).encode(any());
        verify(users, never()).save(any());
    }

    @Test
    void weakPasswordPropagatesEncoderFailureAndDoesNotSave() {
        when(users.existsByEmail(any())).thenReturn(false);
        when(encoder.encode("weak"))
                .thenThrow(new DomainValidationException("Password must be at least 10 characters"));

        assertThatThrownBy(
                        () -> useCase.handle(new RegisterUserCommand(VALID_EMAIL, "weak", "self")))
                .isInstanceOf(DomainValidationException.class);

        verify(users).existsByEmail(any());
        verify(users, never()).save(any());
    }

    @Test
    void emailNormalisationIsAppliedBeforeUniquenessCheck() {
        when(users.existsByEmail(any())).thenReturn(false);
        when(encoder.encode(VALID_PASSWORD)).thenReturn(STUB_HASH);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        useCase.handle(new RegisterUserCommand("  User@Example.COM ", VALID_PASSWORD, "self"));

        ArgumentCaptor<Email> emailCaptor = ArgumentCaptor.forClass(Email.class);
        verify(users).existsByEmail(emailCaptor.capture());
        assertThat(emailCaptor.getValue().value()).isEqualTo("user@example.com");
    }
}
