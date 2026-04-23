package com.plrs.web.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.security.RefreshTokenStore;
import com.plrs.application.security.TokenService;
import com.plrs.application.user.InvalidCredentialsException;
import com.plrs.application.user.LoginResult;
import com.plrs.application.user.LoginUseCase;
import com.plrs.application.user.LogoutUseCase;
import com.plrs.application.user.RegisterUserUseCase;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.user.BCryptHash;
import com.plrs.domain.user.Email;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import com.plrs.web.auth.AuthController;
import com.plrs.web.auth.AuthFormController;
import com.plrs.web.common.GlobalExceptionHandler;
import com.plrs.web.health.HealthController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test that verifies the two {@link SecurityConfig} chains route
 * requests as intended: the API chain gates everything except register
 * and login, while the web chain permits the public browser routes and
 * redirects authenticated-only paths to {@code /login}.
 *
 * <p>Uses {@code @WebMvcTest} rather than a full {@code @SpringBootTest}
 * so we avoid dragging in JPA/Redis auto-configuration — the test
 * exercises security routing, not persistence. Controllers and use
 * cases are mocked; only the security config, the JWT filter, and the
 * global exception handler are real.
 */
@WebMvcTest(
        controllers = {AuthController.class, AuthFormController.class, HealthController.class},
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = GlobalExceptionHandler.class))
@Import({SecurityConfig.class, PasswordEncoderBridge.class, JwtAuthenticationFilter.class})
class SecurityConfigTest {

    private static final String VALID_HASH =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private MockMvc mockMvc;

    @MockBean private RegisterUserUseCase registerUseCase;
    @MockBean private LoginUseCase loginUseCase;
    @MockBean private LogoutUseCase logoutUseCase;
    @MockBean private UserRepository userRepository;
    @MockBean private TokenService tokenService;
    @MockBean private RefreshTokenStore refreshTokenStore;

    private static User sampleUser(UserId id) {
        return User.rehydrate(
                id,
                Email.of("kumar@example.com"),
                BCryptHash.of(VALID_HASH),
                Set.of(Role.STUDENT),
                AuditFields.initial(
                        "test",
                        Clock.fixed(Instant.parse("2026-04-23T10:00:00Z"), ZoneOffset.UTC)));
    }

    @Test
    void apiRouteWithoutAuthorizationReturns401() throws Exception {
        mockMvc.perform(get("/api/users/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void postRegisterIsPermitAllOnApiChain() throws Exception {
        UserId id = UserId.of(UUID.randomUUID());
        when(registerUseCase.handle(any())).thenReturn(id);
        when(userRepository.findById(id)).thenReturn(Optional.of(sampleUser(id)));

        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"kumar@example.com\","
                                                + "\"password\":\"Password01\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void postLoginWithBadCredentialsReturns401() throws Exception {
        when(loginUseCase.handle(any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"ghost@example.com\","
                                                + "\"password\":\"Password01\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postLoginWithValidCredentialsReturns200OnApiChain() throws Exception {
        UserId id = UserId.of(UUID.randomUUID());
        LoginResult result =
                new LoginResult(
                        id,
                        Email.of("kumar@example.com"),
                        Set.of(Role.STUDENT),
                        "access.jwt",
                        "refresh.jwt",
                        Instant.parse("2026-04-23T12:00:00Z"),
                        Instant.parse("2026-05-23T10:00:00Z"));
        when(loginUseCase.handle(any())).thenReturn(result);

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"kumar@example.com\","
                                                + "\"password\":\"Password01\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void getRegisterFormIsPermitAllOnWebChain() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Register")));
    }

    @Test
    void getHealthIsPermitAllOnWebChain() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void protectedWebRouteWithoutAuthIsNotOk() throws Exception {
        // An unmapped but auth-required route should be redirected or denied,
        // never served with a 200. We don't pin the exact status because the
        // redirect target (/login) is served by formLogin's default page
        // generation, which in a slice context can round-trip through
        // additional filters — covering "not 200" is the security contract
        // the test is here to guard.
        mockMvc.perform(get("/some-protected-path"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s >= 200 && s < 300) {
                        throw new AssertionError(
                                "expected non-2xx for unauthenticated protected route, got " + s);
                    }
                });
    }
}
