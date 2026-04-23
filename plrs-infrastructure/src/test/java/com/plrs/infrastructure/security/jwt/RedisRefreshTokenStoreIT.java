package com.plrs.infrastructure.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.application.security.RefreshTokenStore;
import com.plrs.domain.user.UserId;
import com.plrs.infrastructure.testsupport.RedisTestBase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test for the Redis-backed {@link RefreshTokenStore}. Drives
 * the port (not the adapter class) so the Spring port→adapter wiring is
 * itself exercised on every run.
 *
 * <p>Boot context uses a nested {@link RedisRefreshIT} application to
 * avoid the plrs-web reactor cycle; JDBC/JPA auto-configs are excluded
 * because the test only needs Redis + a Clock. Setting
 * {@code plrs.jwt.generate-if-missing=true} keeps {@link JwtKeyProvider}
 * happy — it lives in the same package as this test's scan target and is
 * picked up even though this IT doesn't exercise it.
 */
@SpringBootTest(
        classes = RedisRefreshTokenStoreIT.RedisRefreshIT.class,
        properties = {"plrs.jwt.generate-if-missing=true"})
class RedisRefreshTokenStoreIT extends RedisTestBase {

    @Autowired private RefreshTokenStore store;
    @Autowired private Clock clock;

    private static String newJti() {
        return UUID.randomUUID().toString();
    }

    @Test
    void storeThenIsActiveReturnsTrue() {
        String jti = newJti();
        UserId userId = UserId.newId();

        store.store(jti, userId, Instant.now(clock).plus(Duration.ofMinutes(5)));

        assertThat(store.isActive(jti, userId)).isTrue();
    }

    @Test
    void isActiveReturnsFalseWhenStoredForDifferentUser() {
        String jti = newJti();
        UserId storedUser = UserId.newId();
        UserId probeUser = UserId.newId();

        store.store(jti, storedUser, Instant.now(clock).plus(Duration.ofMinutes(5)));

        assertThat(store.isActive(jti, probeUser)).isFalse();
    }

    @Test
    void revokeThenIsActiveReturnsFalse() {
        String jti = newJti();
        UserId userId = UserId.newId();
        store.store(jti, userId, Instant.now(clock).plus(Duration.ofMinutes(5)));
        assertThat(store.isActive(jti, userId)).isTrue();

        store.revoke(jti);

        assertThat(store.isActive(jti, userId)).isFalse();
    }

    @Test
    void revokeIsIdempotentForUnknownJti() {
        store.revoke(newJti()); // no throw

        assertThat(store.isActive(newJti(), UserId.newId())).isFalse();
    }

    @Test
    void isActiveReturnsFalseForUnknownJti() {
        assertThat(store.isActive(newJti(), UserId.newId())).isFalse();
    }

    @Test
    void storeWithPastExpiryThrowsIllegalArgumentException() {
        String jti = newJti();
        UserId userId = UserId.newId();
        Instant past = Instant.now(clock).minusSeconds(1);

        assertThatThrownBy(() -> store.store(jti, userId, past))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void storedKeyExpiresAfterTtl() {
        String jti = newJti();
        UserId userId = UserId.newId();
        store.store(jti, userId, Instant.now(clock).plus(Duration.ofSeconds(2)));
        assertThat(store.isActive(jti, userId)).isTrue();

        Awaitility.await()
                .atMost(Duration.ofSeconds(6))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(store.isActive(jti, userId)).isFalse());
    }

    @SpringBootApplication(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class
            })
    static class RedisRefreshIT {}
}
