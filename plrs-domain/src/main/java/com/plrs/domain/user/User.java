package com.plrs.domain.user;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

/**
 * Aggregate root representing a PLRS user — the composition of an identity
 * ({@link UserId}), login credentials ({@link Email} + {@link BCryptHash}),
 * the set of {@link Role}s they hold, and audit metadata.
 *
 * <p>The class is deliberately immutable: fields are {@code private final}
 * and the role set is defensively copied via {@link Set#copyOf(java.util.Collection)}
 * (which also enforces an immutable view, so callers cannot mutate it
 * through the accessor). State-changing operations — {@code assignRole}
 * (step 24), and later {@code changeEmail}/{@code changePassword} (Iter 4)
 * — return a new instance rather than mutating the receiver.
 *
 * <p>Two factories are provided:
 *
 * <ul>
 *   <li>{@link #register(Email, BCryptHash, Clock, String)} — the
 *       self-registration path. Assigns a fresh {@link UserId}, defaults
 *       roles to {@code {STUDENT}}, and stamps {@link AuditFields#initial}.
 *   <li>{@link #rehydrate(UserId, Email, BCryptHash, Set, AuditFields)} —
 *       the persistence path. Takes already-materialised values and runs
 *       only validation; the caller is trusted for identity.
 * </ul>
 *
 * <p>Invariants enforced by the canonical constructor:
 *
 * <ul>
 *   <li>{@code id}, {@code email}, {@code passwordHash}, {@code roles},
 *       {@code audit} are all non-null ({@link DomainValidationException}), and
 *   <li>{@code roles} contains at least one role
 *       ({@link DomainInvariantException} — this is an aggregate-level
 *       invariant, not a value-object validation).
 * </ul>
 *
 * <p>Equality is <b>identity-based</b>: two users are equal iff their
 * {@link UserId}s are equal, regardless of whether their emails, hashes,
 * roles, or audit fields differ. This is the conventional aggregate-root
 * equality semantic — the persistent id is the stable identity; other
 * fields can change over the aggregate's lifetime.
 *
 * <p>{@link #toString()} deliberately excludes the password hash so that a
 * user instance accidentally interpolated into a log line does not expose
 * the stored credential material, even in masked form.
 *
 * <p>Traces to: §3.a (aggregates), §3.b (User invariants).
 */
public final class User {

    private final UserId id;
    private final Email email;
    private final BCryptHash passwordHash;
    private final Set<Role> roles;
    private final AuditFields audit;

    private User(
            UserId id,
            Email email,
            BCryptHash passwordHash,
            Set<Role> roles,
            AuditFields audit) {
        if (id == null) {
            throw new DomainValidationException("User id must not be null");
        }
        if (email == null) {
            throw new DomainValidationException("User email must not be null");
        }
        if (passwordHash == null) {
            throw new DomainValidationException("User passwordHash must not be null");
        }
        if (roles == null) {
            throw new DomainValidationException("User roles must not be null");
        }
        if (audit == null) {
            throw new DomainValidationException("User audit must not be null");
        }
        if (roles.isEmpty()) {
            throw new DomainInvariantException("user must have at least one role");
        }
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roles = Set.copyOf(roles);
        this.audit = audit;
    }

    /**
     * Registers a new user. Generates a fresh {@link UserId}, defaults the
     * role set to {@code {STUDENT}}, and stamps an initial audit trio using
     * the supplied clock.
     */
    public static User register(Email email, BCryptHash passwordHash, Clock clock, String createdBy) {
        return new User(
                UserId.newId(),
                email,
                passwordHash,
                Set.of(Role.STUDENT),
                AuditFields.initial(createdBy, clock));
    }

    /**
     * Reconstructs a user from persisted state. All fields are trusted as
     * the caller's responsibility; this path only runs validation.
     */
    public static User rehydrate(
            UserId id,
            Email email,
            BCryptHash passwordHash,
            Set<Role> roles,
            AuditFields audit) {
        return new User(id, email, passwordHash, roles, audit);
    }

    public UserId id() {
        return id;
    }

    public Email email() {
        return email;
    }

    public BCryptHash passwordHash() {
        return passwordHash;
    }

    public Set<Role> roles() {
        return roles;
    }

    public AuditFields audit() {
        return audit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof User other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "User{id=" + id + ", email=" + email + ", roles=" + roles + "}";
    }
}
