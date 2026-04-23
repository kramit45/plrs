package com.plrs.web.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.user.EmailAlreadyRegisteredException;
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
}
