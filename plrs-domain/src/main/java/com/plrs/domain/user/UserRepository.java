package com.plrs.domain.user;

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
}
