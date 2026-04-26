package com.plrs.infrastructure.user;

import com.plrs.domain.user.Email;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter implementing the domain-owned
 * {@link com.plrs.domain.user.UserRepository} port on top of Spring Data
 * JPA. Delegates every method to {@link UserJpaRepository} and converts
 * between the domain aggregate and the JPA entity via {@link UserMapper}.
 *
 * <p>{@link #bumpSkillsVersion} runs a single native UPDATE inside the
 * caller's transaction (PROPAGATION.MANDATORY). The DB-side TRG-3
 * trigger (§3.b.5.3) ensures the column is monotonic.
 *
 * <p>Traces to: §3.a (adapter implements domain port), §3.c (users persistence),
 * §2.e.2.4.2 (TX-01 version bump).
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataUserRepository implements UserRepository {

    private final UserJpaRepository jpa;
    private final UserMapper mapper;

    @PersistenceContext private EntityManager em;

    public SpringDataUserRepository(UserJpaRepository jpa, UserMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public Optional<User> findById(UserId id) {
        return jpa.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return jpa.findByEmail(email.value()).map(mapper::toDomain);
    }

    @Override
    public boolean existsByEmail(Email email) {
        return jpa.existsByEmail(email.value());
    }

    @Override
    public User save(User user) {
        UserJpaEntity saved = jpa.save(mapper.toEntity(user));
        return mapper.toDomain(saved);
    }

    /**
     * Atomically bumps {@code users.user_skills_version} by 1. Must run
     * inside an existing transaction (mandated via
     * {@link Propagation#MANDATORY}); the calling use case
     * (SubmitQuizAttempt, step 90) is already {@code @Transactional}.
     * TRG-3 (§3.b.5.3) on the {@code users} table back-stops monotonicity.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void bumpSkillsVersion(UserId userId) {
        em.createNativeQuery(
                        "UPDATE plrs_ops.users"
                                + " SET user_skills_version = user_skills_version + 1"
                                + " WHERE id = :userId")
                .setParameter("userId", userId.value())
                .executeUpdate();
    }

    /**
     * Reads {@code users.user_skills_version}; returns 0 when the
     * user row is absent so cache-version comparisons treat unknown
     * users as a forced recompute rather than throwing.
     */
    @Override
    public long getSkillsVersion(UserId userId) {
        Object result =
                em.createNativeQuery(
                                "SELECT user_skills_version FROM plrs_ops.users"
                                        + " WHERE id = :userId")
                        .setParameter("userId", userId.value())
                        .getResultStream()
                        .findFirst()
                        .orElse(null);
        return result == null ? 0L : ((Number) result).longValue();
    }

    /** FR-06 lock window. */
    static final Duration FAIL_WINDOW = Duration.ofMinutes(15);

    /** FR-06 lock duration. */
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    /** FR-06 fail-count threshold within {@link #FAIL_WINDOW}. */
    static final int LOCKOUT_THRESHOLD = 5;

    /**
     * Reads {@code locked_until}, NULL-ing an expired lock as a courtesy
     * so callers don't see stale state. The clear runs in a separate
     * statement (after the SELECT) and is best-effort — concurrent
     * recordLoginFailure runs are still correct because the lockout
     * decision uses an atomic single-SQL CASE.
     */
    @Override
    @Transactional
    public Optional<Instant> getLockedUntil(UserId userId) {
        // getResultList tolerates a NULL locked_until column without
        // tripping the stream's NPE-on-null behaviour.
        @SuppressWarnings("unchecked")
        java.util.List<Object> rows =
                em.createNativeQuery(
                                "SELECT locked_until FROM plrs_ops.users WHERE id = :userId")
                        .setParameter("userId", userId.value())
                        .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            return Optional.empty();
        }
        // Hibernate 6 may map TIMESTAMPTZ as either java.sql.Timestamp or
        // java.time.Instant depending on the JDBC driver path; handle both.
        Object raw = rows.get(0);
        Instant lockedUntil =
                raw instanceof Instant i ? i : ((Timestamp) raw).toInstant();
        if (lockedUntil.isAfter(Instant.now())) {
            return Optional.of(lockedUntil);
        }
        // Expired — best-effort clear so the row doesn't carry stale state.
        em.createNativeQuery(
                        "UPDATE plrs_ops.users SET locked_until = NULL"
                                + " WHERE id = :userId AND locked_until <= NOW()")
                .setParameter("userId", userId.value())
                .executeUpdate();
        return Optional.empty();
    }

    /**
     * Atomic failure-count + lockout decision in one SQL with CASE
     * branches. The window-reset rule (last_fail_at older than 15 min →
     * counter restarts at 1) is folded in so the counter never grows
     * unboundedly. The lockout sets {@code locked_until = now + 15 min}
     * once {@code failed_login_count + 1 >= 5} within the window.
     */
    @Override
    @Transactional
    public void recordLoginFailure(UserId userId, Instant now) {
        Timestamp nowTs = Timestamp.from(now);
        Timestamp windowOpen = Timestamp.from(now.minus(FAIL_WINDOW));
        Timestamp lockUntil = Timestamp.from(now.plus(LOCK_DURATION));
        em.createNativeQuery(
                        "UPDATE plrs_ops.users"
                                + " SET failed_login_count = CASE"
                                + "       WHEN last_fail_at IS NULL OR last_fail_at < :windowOpen"
                                + "         THEN 1"
                                + "       ELSE failed_login_count + 1"
                                + "     END,"
                                + "     last_fail_at = :now,"
                                + "     locked_until = CASE"
                                + "       WHEN last_fail_at IS NOT NULL"
                                + "         AND last_fail_at >= :windowOpen"
                                + "         AND failed_login_count + 1 >= :threshold"
                                + "         THEN :lockUntil"
                                + "       ELSE locked_until"
                                + "     END"
                                + " WHERE id = :userId")
                .setParameter("windowOpen", windowOpen)
                .setParameter("now", nowTs)
                .setParameter("threshold", LOCKOUT_THRESHOLD)
                .setParameter("lockUntil", lockUntil)
                .setParameter("userId", userId.value())
                .executeUpdate();
    }

    @Override
    @Transactional
    public void recordLoginSuccess(UserId userId) {
        em.createNativeQuery(
                        "UPDATE plrs_ops.users"
                                + " SET failed_login_count = 0,"
                                + "     last_fail_at = NULL,"
                                + "     locked_until = NULL"
                                + " WHERE id = :userId")
                .setParameter("userId", userId.value())
                .executeUpdate();
    }

    @Override
    @Transactional
    public void unlock(UserId userId) {
        em.createNativeQuery(
                        "UPDATE plrs_ops.users"
                                + " SET locked_until = NULL,"
                                + "     failed_login_count = 0"
                                + " WHERE id = :userId")
                .setParameter("userId", userId.value())
                .executeUpdate();
    }

    @Override
    @Transactional
    public void setResetToken(UserId userId, String token, Instant expiresAt) {
        em.createNativeQuery(
                        "UPDATE plrs_ops.users"
                                + " SET reset_token = :token,"
                                + "     reset_expires_at = :expiresAt"
                                + " WHERE id = :userId")
                .setParameter("token", token)
                .setParameter("expiresAt", Timestamp.from(expiresAt))
                .setParameter("userId", userId.value())
                .executeUpdate();
    }

    @Override
    public Optional<User> findByResetToken(String token) {
        // Native lookup of user_id (reset_token isn't mapped on the JPA
        // entity) followed by the regular findById hydration so the
        // User aggregate carries roles + audit correctly.
        @SuppressWarnings("unchecked")
        java.util.List<Object> rows =
                em.createNativeQuery(
                                "SELECT id FROM plrs_ops.users WHERE reset_token = :token")
                        .setParameter("token", token)
                        .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            return Optional.empty();
        }
        java.util.UUID uid = (java.util.UUID) rows.get(0);
        return findById(UserId.of(uid));
    }

    @Override
    public Optional<Instant> getResetExpiresAt(UserId userId) {
        @SuppressWarnings("unchecked")
        java.util.List<Object> rows =
                em.createNativeQuery(
                                "SELECT reset_expires_at FROM plrs_ops.users WHERE id = :userId")
                        .setParameter("userId", userId.value())
                        .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            return Optional.empty();
        }
        Object raw = rows.get(0);
        return Optional.of(raw instanceof Instant i ? i : ((Timestamp) raw).toInstant());
    }

    @Override
    @Transactional
    public void clearResetToken(UserId userId) {
        em.createNativeQuery(
                        "UPDATE plrs_ops.users"
                                + " SET reset_token = NULL,"
                                + "     reset_expires_at = NULL"
                                + " WHERE id = :userId")
                .setParameter("userId", userId.value())
                .executeUpdate();
    }

    @Override
    @Transactional
    public void updatePasswordHash(UserId userId, String bcryptHash) {
        em.createNativeQuery(
                        "UPDATE plrs_ops.users SET password_hash = :hash WHERE id = :userId")
                .setParameter("hash", bcryptHash)
                .setParameter("userId", userId.value())
                .executeUpdate();
    }
}
