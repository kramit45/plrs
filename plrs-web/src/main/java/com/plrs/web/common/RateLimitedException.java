package com.plrs.web.common;

/**
 * Thrown by {@link PerUserRateLimiter#tryAcquire(java.util.UUID)} when
 * the per-user request budget is exhausted in the current window. The
 * {@code GlobalExceptionHandler} maps it to HTTP 429 with a
 * {@code Retry-After} header.
 */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("rate limit exceeded; retry after " + retryAfterSeconds + " seconds");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
