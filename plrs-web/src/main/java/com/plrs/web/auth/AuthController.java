package com.plrs.web.auth;

import com.plrs.application.user.LoginCommand;
import com.plrs.application.user.LoginResult;
import com.plrs.application.user.LoginUseCase;
import com.plrs.application.user.RegisterUserCommand;
import com.plrs.application.user.RegisterUserUseCase;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
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
 * <p>The {@code findById} read-back after registration is a deliberate
 * defence-in-depth step: if something goes wrong between the save and the
 * response, we fail loudly rather than return a 201 with stale data.
 *
 * <p>Gated with {@link ConditionalOnProperty} on {@code spring.datasource.url}
 * so the no-DB {@code PlrsApplicationTests} smoke test does not try to
 * wire a controller whose use-case beans are also gated off.
 *
 * <p>Traces to: §2.c (registration + login FR), §7 (JWT issuance).
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "spring.datasource.url")
public class AuthController {

    private final RegisterUserUseCase registerUseCase;
    private final LoginUseCase loginUseCase;
    private final UserRepository userRepository;

    public AuthController(
            RegisterUserUseCase registerUseCase,
            LoginUseCase loginUseCase,
            UserRepository userRepository) {
        this.registerUseCase = registerUseCase;
        this.loginUseCase = loginUseCase;
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
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
}
