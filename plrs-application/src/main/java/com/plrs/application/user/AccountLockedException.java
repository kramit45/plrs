package com.plrs.application.user;

import java.time.Instant;

/**
 * Thrown by {@link LoginUseCase} when the target account is currently
 * locked per FR-06. The web layer maps this to HTTP 423 Locked with a
 * {@code Retry-After} header derived from {@link #lockedUntil()}.
 */
public class AccountLockedException extends RuntimeException {

    private final Instant lockedUntil;

    public AccountLockedException(Instant lockedUntil) {
        super("account locked until " + lockedUntil);
        this.lockedUntil = lockedUntil;
    }

    public Instant lockedUntil() {
        return lockedUntil;
    }
}
