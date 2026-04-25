package com.plrs.application.user;

import com.plrs.application.security.PasswordEncoder;
import com.plrs.domain.user.BCryptHash;
import com.plrs.domain.user.Email;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Registers a new user. Orchestrates the three collaborators the domain
 * aggregate cannot (by design) reach on its own:
 *
 * <ol>
 *   <li>Normalise and validate the email via {@link Email#of(String)}.
 *   <li>Short-circuit on duplicate — asking the repository is cheaper than
 *       hashing a password we are about to discard, and it gives the
 *       caller a distinct {@link EmailAlreadyRegisteredException} instead
 *       of waiting for a database unique-constraint violation.
 *   <li>Encode the raw password via the {@link PasswordEncoder} port
 *       (which itself runs the password policy), wrap in a
 *       {@link BCryptHash}, and call {@link User#register} to materialise
 *       the aggregate with the {@code STUDENT} default role and an
 *       initial audit stamp.
 *   <li>Persist via the {@link UserRepository} port and return the id.
 * </ol>
 *
 * <p>Note on the existence check: because Redis is not involved, this is
 * a read-then-write sequence with an inherent race. A concurrent second
 * registration can slip between the check and the save; the repository's
 * unique constraint will raise a
 * {@link org.springframework.dao.DataIntegrityViolationException} in that
 * case. The web layer (step 35+) maps both conditions to the same HTTP
 * 409 response so the distinction is invisible to clients. Making the
 * primary check explicit keeps the happy path cheap and the error
 * diagnostic precise.
 *
 * <p>Traces to: §2.c (user registration FR), §3.b (email uniqueness
 * invariant).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class RegisterUserUseCase {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public RegisterUserUseCase(UserRepository users, PasswordEncoder encoder, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
    }

    @com.plrs.application.audit.Auditable(action = "USER_REGISTERED", entityType = "user")
    public UserId handle(RegisterUserCommand cmd) {
        Email email = Email.of(cmd.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email.value());
        }
        BCryptHash hash = encoder.encode(cmd.rawPassword());
        User user = User.register(email, hash, clock, cmd.registrantContext());
        return users.save(user).id();
    }
}
