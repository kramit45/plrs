package com.plrs.application.security;

/**
 * Thrown by {@link TokenService} when a JWT cannot be trusted: signature
 * mismatch, expired, wrong issuer or audience, tampered payload, or the
 * token type (access vs refresh) does not match the verifier's
 * expectation. Unchecked so callers do not have to thread a checked
 * {@code throws} clause through authentication filters; the web layer
 * handles it as an HTTP 401.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
