package com.plrs.web.auth;

import com.plrs.application.user.LoginCommand;
import com.plrs.application.user.LoginResult;
import com.plrs.application.user.LoginUseCase;
import com.plrs.application.user.LogoutCommand;
import com.plrs.application.user.LogoutUseCase;
import com.plrs.application.user.RegisterUserCommand;
import com.plrs.application.user.RegisterUserUseCase;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for authentication flows — registration and login in Iter 1.
 *
 * <p>{@code POST /api/auth/register}
 *
 * <ul>
 *   <li>Request: {@link RegisterRequest} — non-blank email and password.
 *   <li>Success: 201 Created, {@link RegisterResponse} body, {@code Location}
 *       pointing at the future {@code /api/users/{id}} resource.
 *   <li>Failure: 400 for validation, 409 for duplicate email.
 * </ul>
 *
 * <p>{@code POST /api/auth/login}
 *
 * <ul>
 *   <li>Request: {@link LoginRequest} — non-blank email and password.
 *   <li>Success: 200 OK, {@link LoginResponse} body with both tokens,
 *       explicit expiry instants, user identity and roles.
 *   <li>Failure: 400 for validation, 401 for bad credentials (intentionally
 *       indistinguishable between "unknown email" and "wrong password" to
 *       prevent account enumeration; see
 *       {@link com.plrs.application.user.LoginUseCase}).
 * </ul>
 *
 * <p>{@code POST /api/auth/logout}
 *
 * <ul>
 *   <li>Request: {@link LogoutRequest} — non-blank refresh token.
 *   <li>Success: 204 No Content — the refresh-token allow-list entry is
 *       revoked (idempotent; a second logout is also a 204).
 *   <li>Failure: 400 for validation, 401 for an invalid or expired token.
 * </ul>
 *
 * <p>The {@code findById} read-back after registration is a deliberate
 * defence-in-depth step: if something goes wrong between the save and the
 * response, we fail loudly rather than return a 201 with stale data.
 *
 * <p>Gated with {@link ConditionalOnProperty} on {@code spring.datasource.url}
 * so the no-DB {@code PlrsApplicationTests} smoke test does not try to
 * wire a controller whose use-case beans are also gated off.
 *
 * <p>Traces to: §2.c (registration + login + logout FR), §7 (JWT
 * issuance and refresh-token allow-list revocation).
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "spring.datasource.url")
@Tag(name = "Authentication", description = "Register, log in, and revoke refresh tokens")
public class AuthController {

    private final RegisterUserUseCase registerUseCase;
    private final LoginUseCase loginUseCase;
    private final LogoutUseCase logoutUseCase;
    private final UserRepository userRepository;

    public AuthController(
            RegisterUserUseCase registerUseCase,
            LoginUseCase loginUseCase,
            LogoutUseCase logoutUseCase,
            UserRepository userRepository) {
        this.registerUseCase = registerUseCase;
        this.loginUseCase = loginUseCase;
        this.logoutUseCase = logoutUseCase;
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new STUDENT user")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "User created"),
        @ApiResponse(responseCode = "400", description = "Validation failed"),
        @ApiResponse(responseCode = "409", description = "Email already in use")
    })
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest req) {
        UserId id =
                registerUseCase.handle(
                        new RegisterUserCommand(req.email(), req.password(), "api-registration"));
        User saved =
                userRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "user vanished between save and read-back: " + id));
        URI location = URI.create("/api/users/" + id.value());
        return ResponseEntity.created(location)
                .body(new RegisterResponse(id.value(), saved.email().value()));
    }

    @PostMapping("/login")
    @Operation(
            summary = "Exchange credentials for an access + refresh token pair",
            description =
                    "401 is intentionally indistinguishable between unknown email and wrong password "
                            + "to prevent account enumeration. Per-IP rate limit applies (NFR-31).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login successful"),
        @ApiResponse(responseCode = "400", description = "Validation failed"),
        @ApiResponse(responseCode = "401", description = "Bad credentials"),
        @ApiResponse(responseCode = "423", description = "Account locked (FR-06)"),
        @ApiResponse(responseCode = "429", description = "Per-IP login rate limit exceeded")
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        LoginResult r = loginUseCase.handle(new LoginCommand(req.email(), req.password()));
        Set<String> roleNames = r.roles().stream().map(Role::name).collect(Collectors.toSet());
        return ResponseEntity.ok(
                new LoginResponse(
                        r.accessToken(),
                        r.refreshToken(),
                        "Bearer",
                        r.userId().value(),
                        r.email().value(),
                        roleNames,
                        r.accessExpiresAt(),
                        r.refreshExpiresAt()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke a refresh token (idempotent — second call also returns 204)")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Refresh token revoked"),
        @ApiResponse(responseCode = "400", description = "Validation failed"),
        @ApiResponse(responseCode = "401", description = "Invalid or expired token")
    })
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest req) {
        logoutUseCase.handle(new LogoutCommand(req.refreshToken()));
        return ResponseEntity.noContent().build();
    }
}
