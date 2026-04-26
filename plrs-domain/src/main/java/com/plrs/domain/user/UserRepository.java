package com.plrs.domain.user;

import java.time.Instant;
import java.util.Optional;

/**
 * Port for loading and persisting {@link User} aggregates. The interface is
 * declared in the domain module — alongside the aggregate itself — so
 * application services depend only on the domain abstraction. The Spring
 * Data JPA adapter that implements this port lives in the infrastructure
 * module (step 29).
 *
 * <p>The port exposes the narrow surface the application layer needs for
 * registration, login and role assignment in Iter 1: identity lookup,
 * email lookup, an email-existence probe (cheaper than materialising the
 * aggregate when the caller only needs to enforce uniqueness), and a
 * save that returns the persisted aggregate. Listing, pagination, and
 * deletion are deliberately out of scope until a use case demands them.
 *
 * <p>Traces to: §3.a (hexagonal — domain-owned ports), §3.b (email
 * uniqueness invariant).
 */
public interface UserRepository {

    /**
     * Loads a user by its persistent identity.
     *
     * @return the user if present, or {@link Optional#empty()} otherwise
     */
    Optional<User> findById(UserId id);

    /**
     * Loads a user by email. Email equality is the normalised lowercase form
     * enforced by {@link Email#of(String)}, so the adapter can rely on exact
     * string match and does not need a case-folding index.
     *
     * @return the user if present, or {@link Optional#empty()} otherwise
     */
    Optional<User> findByEmail(Email email);

    /**
     * Cheap uniqueness probe for registration flows that only need to know
     * whether the email is already taken without materialising the full
     * aggregate.
     */
    boolean existsByEmail(Email email);

    /**
     * Persists the aggregate and returns the stored representation. The
     * aggregate's identity ({@link User#id()}) is never changed by this
     * call — the returned instance has the same {@link UserId} as the
     * input. Audit fields on the input are written as-is; the service
     * layer is responsible for stamping them before calling save.
     */
    User save(User user);

    /**
     * Atomically increments {@code users.user_skills_version} by 1 for
     * the given user. Used by SubmitQuizAttempt (step 90) to flip a
     * cache-bust signal in the same transaction as the mastery upsert
     * (TX-01, §2.e.2.4.2). The DB-level monotonic trigger (TRG-3,
     * §3.b.5.3) guards against accidental decreases.
     */
    void bumpSkillsVersion(UserId userId);

    /**
     * Reads the current {@code users.user_skills_version} for the
     * given user. Used by the recommender's cache layer to compare
     * against the version stamped on a cached top-N entry (§2.e.2.3.3).
     * Returns {@code 0} when the user is unknown — the caller will
     * always treat the missing-cache case as a recompute, so this
     * keeps the API total without requiring an Optional.
     */
    long getSkillsVersion(UserId userId);

    /**
     * Reads {@code users.locked_until} for the FR-06 lock check. Returns
     * empty when not locked OR when the lock has already expired (caller
     * doesn't need to worry about the expiry gate). The adapter is
     * expected to NULL-out an expired lock as a courtesy on read so the
     * column doesn't carry stale state.
     */
    Optional<Instant> getLockedUntil(UserId userId);

    /**
     * Records one failed login attempt and applies the FR-06 lockout
     * rule (≥ 5 failures within 15 min → set {@code locked_until = now +
     * 15 min}). Implementation is a single SQL statement with CASE so
     * the read-decide-write happens atomically — no application-side
     * race window.
     */
    void recordLoginFailure(UserId userId, Instant now);

    /**
     * Resets the lockout state on a successful login: zero
     * {@code failed_login_count}, NULL {@code last_fail_at} and
     * {@code locked_until}.
     */
    void recordLoginSuccess(UserId userId);

    /** ADMIN-only manual unlock: clears {@code locked_until} and the failure counter. */
    void unlock(UserId userId);
}
