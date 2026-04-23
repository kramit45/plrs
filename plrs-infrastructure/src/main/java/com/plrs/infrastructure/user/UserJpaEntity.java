package com.plrs.infrastructure.user;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA entity mirror of the {@code com.plrs.domain.user.User} aggregate. The
 * mapper in this package is the sole bridge between the two — call sites
 * outside infrastructure never see this class, so the domain remains pure.
 *
 * <p>Business fields have no setters. Hibernate populates them via the
 * protected no-arg constructor + reflection; application code uses the
 * all-args constructor through the mapper. Mutation happens by building a
 * fresh entity (round-tripping through the aggregate) rather than by
 * in-place field edits, matching the aggregate's own immutable stance.
 *
 * <p>{@link #roles} is eager-fetched because {@code User.roles()} is part of
 * the aggregate's core identity in Iter 1 (auth flows read roles on every
 * request). {@code cascade = ALL} plus {@code orphanRemoval = true} keeps
 * the join table in lockstep with the aggregate's role set: a save writes
 * any added roles, and a role dropped from the set disappears from the
 * join table (the aggregate itself only adds roles today, but the wiring
 * is future-proof for step 24+ changes).
 *
 * <p>Traces to: §3.a (aggregates), §3.c (users schema).
 */
@Entity
@Table(name = "users", schema = "plrs_ops")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @OneToMany(
            mappedBy = "user",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER)
    private Set<UserRoleJpaEntity> roles = new HashSet<>();

    @Embedded
    private AuditFieldsEmbeddable audit;

    public UserJpaEntity(
            UUID id,
            String email,
            String passwordHash,
            Set<UserRoleJpaEntity> roles,
            AuditFieldsEmbeddable audit) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roles = roles;
        this.audit = audit;
    }
}
