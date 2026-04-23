package com.plrs.web.auth;

import com.plrs.application.user.RegisterUserCommand;
import com.plrs.application.user.RegisterUserUseCase;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for authentication flows. Iter 1 exposes only registration;
 * login and refresh land in steps 37 and 38.
 *
 * <p>{@code POST /api/auth/register}
 *
 * <ul>
 *   <li>Request: {@link RegisterRequest} — non-blank email and password.
 *   <li>Success: 201 Created, {@link RegisterResponse} body, {@code Location}
 *       pointing at the future {@code /api/users/{id}} resource (the read
 *       endpoint is not built in Iter 1; the header follows REST
 *       conventions regardless).
 *   <li>Failure: 400 for validation, 409 for duplicate email; see
 *       {@link com.plrs.web.common.GlobalExceptionHandler}.
 * </ul>
 *
 * <p>The {@code findById} read-back after the use case is a deliberate
 * defence-in-depth step: if something goes wrong between the save and the
 * response (connection drop, mapper regression), we fail loudly rather
 * than return a 201 with stale data.
 *
 * <p>Gated with {@link ConditionalOnProperty} on {@code spring.datasource.url}
 * so the no-DB {@code PlrsApplicationTests} smoke test does not try to
 * wire a controller whose use-case bean is also gated off. The real
 * application always sets the property.
 *
 * <p>Traces to: §2.c (user registration FR).
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "spring.datasource.url")
public class AuthController {

    private final RegisterUserUseCase registerUseCase;
    private final UserRepository userRepository;

    public AuthController(RegisterUserUseCase registerUseCase, UserRepository userRepository) {
        this.registerUseCase = registerUseCase;
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
}
