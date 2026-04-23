package com.plrs.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.security.InvalidTokenException;
import com.plrs.application.user.EmailAlreadyRegisteredException;
import com.plrs.application.user.InvalidCredentialsException;
import com.plrs.application.user.LoginResult;
import com.plrs.application.user.LoginUseCase;
import com.plrs.application.user.LogoutCommand;
import com.plrs.application.user.LogoutUseCase;
import com.plrs.application.user.RegisterUserUseCase;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.user.BCryptHash;
import com.plrs.domain.user.Email;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import com.plrs.web.common.GlobalExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = AuthController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = GlobalExceptionHandler.class))
class AuthControllerTest {

    private static final String VALID_HASH =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";
    private static final Instant T0 = Instant.parse("2026-04-23T10:00:00Z");

    @Autowired private MockMvc mockMvc;

    @MockBean private RegisterUserUseCase registerUseCase;
    @MockBean private LoginUseCase loginUseCase;
    @MockBean private LogoutUseCase logoutUseCase;
    @MockBean private UserRepository userRepository;

    private static User userWithId(UserId id, String email) {
        return User.rehydrate(
                id,
                Email.of(email),
                BCryptHash.of(VALID_HASH),
                Set.of(Role.STUDENT),
                com.plrs.domain.common.AuditFields.initial(
                        "api-registration", Clock.fixed(T0, ZoneOffset.UTC)));
    }

    @Test
    void postRegisterHappyPathReturns201WithLocationAndBody() throws Exception {
        UserId newId = UserId.of(UUID.randomUUID());
        when(registerUseCase.handle(any())).thenReturn(newId);
        when(userRepository.findById(newId))
                .thenReturn(Optional.of(userWithId(newId, "kumar@example.com")));

        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"kumar@example.com\","
                                                + "\"password\":\"Password01\"}"))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                        "Location", "/api/users/" + newId.value()))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(newId.value().toString()))
                .andExpect(jsonPath("$.email").value("kumar@example.com"));
    }

    @Test
    void postRegisterInvalidJsonReturns400Problem() throws Exception {
        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Malformed request body"));
    }

    @Test
    void postRegisterMissingFieldsReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.email", Matchers.notNullValue()))
                .andExpect(jsonPath("$.errors.password", Matchers.notNullValue()));
    }

    @Test
    void postRegisterDuplicateEmailReturns409() throws Exception {
        when(registerUseCase.handle(any()))
                .thenThrow(new EmailAlreadyRegisteredException("dup@example.com"));

        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"dup@example.com\","
                                                + "\"password\":\"Password01\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Email already registered"))
                .andExpect(jsonPath("$.email").value("dup@example.com"));
    }

    @Test
    void postRegisterWeakPasswordReturns400() throws Exception {
        when(registerUseCase.handle(any()))
                .thenThrow(new DomainValidationException("Password must be at least 10 characters"));

        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"kumar@example.com\","
                                                + "\"password\":\"weak1A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void postRegisterInvalidEmailFormatReturns400() throws Exception {
        when(registerUseCase.handle(any()))
                .thenThrow(new DomainValidationException("Email format is invalid: not-an-email"));

        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"not-an-email\","
                                                + "\"password\":\"Password01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    private static LoginResult stubLoginResult(UserId id, String email) {
        Instant access = T0.plusSeconds(7200);
        Instant refresh = T0.plusSeconds(2592000);
        return new LoginResult(
                id,
                Email.of(email),
                Set.of(Role.STUDENT, Role.INSTRUCTOR),
                "access.jwt.token",
                "refresh.jwt.token",
                access,
                refresh);
    }

    @Test
    void postLoginHappyPathReturnsTokens() throws Exception {
        UserId id = UserId.of(UUID.randomUUID());
        when(loginUseCase.handle(any())).thenReturn(stubLoginResult(id, "kumar@example.com"));

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"kumar@example.com\","
                                                + "\"password\":\"Password01\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").value("access.jwt.token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh.jwt.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").value(id.value().toString()))
                .andExpect(jsonPath("$.email").value("kumar@example.com"))
                .andExpect(jsonPath("$.roles", Matchers.containsInAnyOrder("STUDENT", "INSTRUCTOR")))
                .andExpect(jsonPath("$.accessExpiresAt").value("2026-04-23T12:00:00Z"))
                .andExpect(jsonPath("$.refreshExpiresAt").value("2026-05-23T10:00:00Z"));
    }

    @Test
    void postLoginWrongPasswordReturns401WithGenericProblem() throws Exception {
        when(loginUseCase.handle(any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"kumar@example.com\","
                                                + "\"password\":\"Wrong12345\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("kumar"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Wrong12345"))));
    }

    @Test
    void postLoginUnknownEmailReturns401WithSameBody() throws Exception {
        when(loginUseCase.handle(any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"ghost@example.com\","
                                                + "\"password\":\"Password01\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void postLoginMissingFieldsReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.email", Matchers.notNullValue()))
                .andExpect(jsonPath("$.errors.password", Matchers.notNullValue()));
    }

    @Test
    void postLoginMalformedJsonReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Malformed request body"));
    }

    @Test
    void postLoginResponseDoesNotContainPasswordHash() throws Exception {
        UserId id = UserId.of(UUID.randomUUID());
        when(loginUseCase.handle(any())).thenReturn(stubLoginResult(id, "kumar@example.com"));

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"kumar@example.com\","
                                                + "\"password\":\"Password01\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(Matchers.not(Matchers.containsString("passwordHash"))))
                .andExpect(
                        content().string(Matchers.not(Matchers.containsString(VALID_HASH))));
    }

    @Test
    void postLogoutHappyPathReturns204AndCallsUseCaseWithToken() throws Exception {
        String token = "refresh.jwt.token.abc";

        mockMvc.perform(
                        post("/api/auth/logout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"" + token + "\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        ArgumentCaptor<LogoutCommand> captor = ArgumentCaptor.forClass(LogoutCommand.class);
        verify(logoutUseCase).handle(captor.capture());
        assertThat(captor.getValue().refreshToken()).isEqualTo(token);
    }

    @Test
    void postLogoutWithInvalidTokenReturns401() throws Exception {
        doThrow(new InvalidTokenException("bad signature"))
                .when(logoutUseCase)
                .handle(any());

        mockMvc.perform(
                        post("/api/auth/logout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"bogus.token.value\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Invalid or expired token"));
    }

    @Test
    void postLogoutMissingRefreshTokenReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/auth/logout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.refreshToken", Matchers.notNullValue()));
    }

    @Test
    void postLogoutBlankRefreshTokenReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/auth/logout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.refreshToken", Matchers.notNullValue()));
    }

    @Test
    void postLogoutResponseNeverEchoesTokenValue() throws Exception {
        String token = "very-secret-refresh-jwt-xyz";
        doThrow(new InvalidTokenException("expired"))
                .when(logoutUseCase)
                .handle(any());

        mockMvc.perform(
                        post("/api/auth/logout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(Matchers.not(Matchers.containsString(token))));
    }
}
