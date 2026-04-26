package com.plrs.web.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sliding-window per-IP rate limiter for the login endpoint.
 * NFR-31 extension: 10 attempts per 5 minutes per source IP.
 *
 * <p>Implementation mirrors {@code PerUserRateLimiter}: each IP maps
 * to an {@link ArrayDeque} of recent attempt timestamps; entries
 * older than {@link #WINDOW} are dropped from the head before the
 * size check. Per-deque {@code synchronized} keeps it thread-safe
 * without a global lock.
 *
 * <p>{@link #cleanup()} runs every 10 minutes and drops buckets
 * whose newest entry is older than 1 hour, so a long-tail of
 * one-shot probe IPs doesn't grow the map indefinitely.
 *
 * <p>Traces to: NFR-31 extended to login.
 */
@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class IpRateLimiter {

    /** Maximum login attempts per IP per window. */
    public static final int LIMIT_PER_WINDOW = 10;

    /** Sliding window length. */
    public static final Duration WINDOW = Duration.ofMinutes(5);

    /** Buckets idle for longer than this are dropped by {@link #cleanup()}. */
    static final Duration IDLE_DROP = Duration.ofHours(1);

    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> attempts =
            new ConcurrentHashMap<>();

    public IpRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records a login attempt for {@code ip}. Returns {@code true} if
     * the attempt is within budget, {@code false} if the IP has
     * already issued {@link #LIMIT_PER_WINDOW} attempts within
     * {@link #WINDOW}.
     */
    public boolean tryAcquire(String ip) {
        Instant now = Instant.now(clock);
        Instant windowStart = now.minus(WINDOW);
        Deque<Instant> deque =
                attempts.computeIfAbsent(
                        ip, x -> new ArrayDeque<>(LIMIT_PER_WINDOW + 1));
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().isBefore(windowStart)) {
                deque.pollFirst();
            }
            if (deque.size() >= LIMIT_PER_WINDOW) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    /**
     * Seconds until the oldest in-window attempt expires — what the
     * 429 response should set {@code Retry-After} to so the client
     * can back off precisely.
     */
    public long retryAfterSeconds(String ip) {
        Deque<Instant> deque = attempts.get(ip);
        if (deque == null) {
            return 1L;
        }
        synchronized (deque) {
            if (deque.isEmpty()) {
                return 1L;
            }
            Instant oldest = deque.peekFirst();
            long secs = Duration.between(Instant.now(clock), oldest.plus(WINDOW)).toSeconds();
            return Math.max(1L, secs);
        }
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    public void cleanup() {
        Instant cutoff = Instant.now(clock).minus(IDLE_DROP);
        Iterator<Map.Entry<String, Deque<Instant>>> it = attempts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Deque<Instant>> e = it.next();
            Deque<Instant> deque = e.getValue();
            synchronized (deque) {
                if (deque.isEmpty() || deque.peekLast().isBefore(cutoff)) {
                    it.remove();
                }
            }
        }
    }

    /** Test hook to drop all per-IP state. */
    public void clear() {
        attempts.clear();
    }
}
