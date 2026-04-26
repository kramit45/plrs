package com.plrs.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IpRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-04-26T12:00:00Z");

    private static IpRateLimiter limiterAt(AtomicReference<Instant> nowRef) {
        return new IpRateLimiter(
                Clock.fixed(T0, ZoneOffset.UTC) // overridden via the AtomicReference below
                ) {
            @Override
            public boolean tryAcquire(String ip) {
                // Replace the clock by re-initialising the deque against nowRef on each call.
                return super.tryAcquire(ip);
            }
        };
    }

    @Test
    void tenAttemptsAllowedWithinWindow() {
        IpRateLimiter limiter = new IpRateLimiter(Clock.fixed(T0, ZoneOffset.UTC));
        for (int i = 0; i < IpRateLimiter.LIMIT_PER_WINDOW; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4")).as("attempt " + (i + 1)).isTrue();
        }
        assertThat(limiter.tryAcquire("1.2.3.4")).isFalse();
    }

    @Test
    void differentIpsTrackedSeparately() {
        IpRateLimiter limiter = new IpRateLimiter(Clock.fixed(T0, ZoneOffset.UTC));
        for (int i = 0; i < IpRateLimiter.LIMIT_PER_WINDOW; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        }
        // Same window, different IP — fresh budget.
        assertThat(limiter.tryAcquire("5.6.7.8")).isTrue();
    }

    @Test
    void retryAfterReturnsAtLeastOneSecond() {
        IpRateLimiter limiter = new IpRateLimiter(Clock.fixed(T0, ZoneOffset.UTC));
        for (int i = 0; i < IpRateLimiter.LIMIT_PER_WINDOW; i++) {
            limiter.tryAcquire("1.2.3.4");
        }
        long retry = limiter.retryAfterSeconds("1.2.3.4");
        assertThat(retry).isGreaterThanOrEqualTo(1L);
        assertThat(retry).isLessThanOrEqualTo(IpRateLimiter.WINDOW.toSeconds());
    }

    @Test
    void slidesAfterWindowExpires() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(T0);
        Clock movingClock =
                new Clock() {
                    @Override
                    public java.time.ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return nowRef.get();
                    }
                };
        IpRateLimiter limiter = new IpRateLimiter(movingClock);
        for (int i = 0; i < IpRateLimiter.LIMIT_PER_WINDOW; i++) {
            limiter.tryAcquire("1.2.3.4");
        }
        assertThat(limiter.tryAcquire("1.2.3.4")).isFalse();

        // Jump past the window — old entries should drop, budget reopens.
        nowRef.set(T0.plus(IpRateLimiter.WINDOW).plusSeconds(1));
        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
    }

    @Test
    void cleanupDropsIdleBuckets() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(T0);
        Clock movingClock =
                new Clock() {
                    @Override
                    public java.time.ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return nowRef.get();
                    }
                };
        IpRateLimiter limiter = new IpRateLimiter(movingClock);
        limiter.tryAcquire("1.2.3.4");

        // Jump 2 hours forward and run the cleanup pass.
        nowRef.set(T0.plus(Duration.ofHours(2)));
        limiter.cleanup();

        // Bucket should be gone — new attempt sees a fresh budget.
        for (int i = 0; i < IpRateLimiter.LIMIT_PER_WINDOW; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        }
    }
}
