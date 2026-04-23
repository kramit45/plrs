package com.plrs.infrastructure.user;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA entity for the {@code plrs_ops.user_roles} join table. Rather than a
 * {@code @ElementCollection<Role>}, a first-class entity is used so the
 * {@code assigned_at} column declared by {@code V3__user_roles.sql} has a
 * clean home — element collections don't support per-row columns beyond
 * the value itself.
 *
 * <p>{@link UserRoleId} is the composite key; {@link MapsId @MapsId("userId")}
 * ties the {@code user_id} column to the key's {@code userId} so Hibernate
 * does not create a duplicate column for the {@link #user} association.
 *
 * <p>Traces to: §3.c (user_roles), §7 (additive role model).
 */
@Entity
@Table(name = "user_roles", schema = "plrs_ops")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRoleJpaEntity {

    @EmbeddedId
    private UserRoleId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserJpaEntity user;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    public UserRoleJpaEntity(UserRoleId id, UserJpaEntity user, Instant assignedAt) {
        this.id = id;
        this.user = user;
        this.assignedAt = assignedAt;
    }
}
