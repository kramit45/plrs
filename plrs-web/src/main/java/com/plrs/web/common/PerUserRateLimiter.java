package com.plrs.web.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Sliding-window per-user rate limiter, bounded at
 * {@link #LIMIT_PER_WINDOW} requests per {@link #WINDOW}.
 *
 * <p>Implementation: each user maps to an {@link ArrayDeque} of recent
 * request timestamps. {@link #tryAcquire(UUID)} drops entries older
 * than the window from the head, then either appends and returns
 * (under-budget) or throws {@link RateLimitedException} (over).
 *
 * <p>Per-user deque locking via {@code synchronized} on the deque
 * keeps the operation thread-safe without needing a global lock; the
 * outer {@link ConcurrentHashMap#computeIfAbsent} is atomic. NFR-31
 * documented limit; tighter algorithms (Bucket4j) can replace this
 * later without changing the public API.
 *
 * <p>Traces to: NFR-31 (per-user rate limit).
 */
@Component
public class PerUserRateLimiter {

    /** Maximum requests per user per window. */
    public static final int LIMIT_PER_WINDOW = 20;

    /** Sliding window length. */
    public static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final ConcurrentHashMap<UUID, Deque<Instant>> requests = new ConcurrentHashMap<>();

    public PerUserRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records a request for {@code userId}, throwing
     * {@link RateLimitedException} if the user has already issued
     * {@link #LIMIT_PER_WINDOW} requests within the most recent
     * {@link #WINDOW}.
     */
    public void tryAcquire(UUID userId) {
        Instant now = Instant.now(clock);
        Instant windowStart = now.minus(WINDOW);
        Deque<Instant> deque =
                requests.computeIfAbsent(userId, id -> new ArrayDeque<>(LIMIT_PER_WINDOW + 1));
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().isBefore(windowStart)) {
                deque.pollFirst();
            }
            if (deque.size() >= LIMIT_PER_WINDOW) {
                Instant oldest = deque.peekFirst();
                long retry =
                        Math.max(
                                1L,
                                Duration.between(now, oldest.plus(WINDOW)).toSeconds());
                throw new RateLimitedException(retry);
            }
            deque.addLast(now);
        }
    }

    /** Test hook to drop all per-user state. */
    public void clear() {
        requests.clear();
    }
}
