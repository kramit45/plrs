package com.plrs.application.user;

/**
 * Thrown by {@link LoginUseCase} when the supplied credentials do not
 * match a registered user. The message is fixed to {@code "Invalid email
 * or password"} and deliberately never includes the attempted email — a
 * leaked enumeration, especially through server logs or error responses,
 * would let an attacker discover which addresses exist in the system by
 * observing which attempts produce a distinctive message.
 *
 * <p>Runtime-scoped so login filters / controllers do not carry a checked
 * throws clause. The web layer translates it to HTTP 401.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
