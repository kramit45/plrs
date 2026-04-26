package com.plrs.application.user;

/**
 * Thrown by {@link ConfirmPasswordResetUseCase} when the supplied
 * token is unknown, expired, or already consumed. The web layer maps
 * this to 400 Bad Request — the response intentionally does not
 * distinguish "unknown" from "expired" so an attacker probing tokens
 * cannot tell which.
 */
public class InvalidResetTokenException extends RuntimeException {

    public InvalidResetTokenException() {
        super("Invalid or expired reset token");
    }
}
