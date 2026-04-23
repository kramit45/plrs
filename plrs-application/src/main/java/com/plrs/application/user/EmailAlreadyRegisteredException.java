package com.plrs.application.user;

/**
 * Thrown by {@link RegisterUserUseCase} when an attempt is made to register
 * an email that already exists in the repository. The web layer translates
 * this to HTTP 409 Conflict in step 35+.
 *
 * <p>Runtime-scoped so the registration controller does not have to declare
 * a checked throws clause; this is a user-driven outcome (not an
 * exceptional bug) and deserves its own dedicated type so catch sites can
 * distinguish it from lower-level
 * {@link org.springframework.dao.DataIntegrityViolationException}s.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    private final String email;

    public EmailAlreadyRegisteredException(String email) {
        super("email already registered: " + email);
        this.email = email;
    }

    public String email() {
        return email;
    }
}
