package com.plrs.infrastructure.user;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.user.BCryptHash;
import com.plrs.domain.user.Email;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Bridge between the {@link User} aggregate (domain) and {@link UserJpaEntity}
 * (infrastructure). The two conversions handle two cases that cannot be
 * auto-derived from a setter-based bean description:
 *
 * <ul>
 *   <li>{@link User} exposes only private constructors and a static
 *       {@code rehydrate} factory, so the reverse mapping must call that
 *       factory explicitly.
 *   <li>{@link UserJpaEntity} owns a bidirectional {@code @OneToMany} to
 *       {@link UserRoleJpaEntity}; each child needs a back-reference to its
 *       parent and the parent's {@link AuditFields#updatedAt() updatedAt}
 *       as the pragmatic {@code assigned_at} default.
 * </ul>
 *
 * <p>Implemented as a Spring {@link Component} rather than a MapStruct
 * {@code @Mapper} interface: MapStruct's code generator cannot produce a
 * valid implementation when the target type (here, {@link User}) has no
 * public constructor or setters — the factory-only design of the domain
 * aggregate is deliberate (step 23) and takes precedence over matching
 * the step prompt's wording. The helpers below are single-responsibility
 * methods, kept as instance methods for symmetry with the two public
 * conversions and so callers who need a one-off conversion (for example
 * in a DTO mapper) can depend on this bean without reimplementing the
 * {@code String}↔{@link Email} bridge.
 *
 * <p>Traces to: §3.a (infra maps domain ↔ JPA).
 */
@Component
public class UserMapper {

    public User toDomain(UserJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return User.rehydrate(
                toUserId(entity.getId()),
                toEmail(entity.getEmail()),
                toBCryptHash(entity.getPasswordHash()),
                entity.getRoles().stream()
                        .map(role -> role.getId().getRole())
                        .collect(Collectors.toUnmodifiableSet()),
                toAuditFields(entity.getAudit()));
    }

    public UserJpaEntity toEntity(User user) {
        if (user == null) {
            return null;
        }
        UserJpaEntity entity =
                new UserJpaEntity(
                        user.id().value(),
                        user.email().value(),
                        user.passwordHash().value(),
                        new HashSet<>(),
                        toAuditFieldsEmbeddable(user.audit()));
        Instant assignedAt = user.audit().updatedAt();
        for (Role role : user.roles()) {
            entity.getRoles()
                    .add(
                            new UserRoleJpaEntity(
                                    new UserRoleId(user.id().value(), role), entity, assignedAt));
        }
        return entity;
    }

    public UserId toUserId(UUID value) {
        return value == null ? null : UserId.of(value);
    }

    public Email toEmail(String value) {
        return value == null ? null : Email.of(value);
    }

    public BCryptHash toBCryptHash(String value) {
        return value == null ? null : BCryptHash.of(value);
    }

    /**
     * Reconstructs an {@link AuditFields} from its embeddable counterpart.
     * The domain type offers no all-fields reconstitute factory by design —
     * the only supported lifecycle is {@code initial} + {@code touched}.
     * This is simulated by initialising at {@code createdAt} and touching
     * to {@code updatedAt}; the DB CHECK constraint
     * ({@code users_updated_after_created}) guarantees
     * {@code updatedAt >= createdAt}, so the touch is always valid.
     */
    public AuditFields toAuditFields(AuditFieldsEmbeddable embeddable) {
        if (embeddable == null) {
            return null;
        }
        return AuditFields.initial(embeddable.getCreatedBy(), embeddable.getCreatedAt())
                .touchedAt(embeddable.getUpdatedAt());
    }

    public AuditFieldsEmbeddable toAuditFieldsEmbeddable(AuditFields audit) {
        if (audit == null) {
            return null;
        }
        return new AuditFieldsEmbeddable(audit.createdAt(), audit.updatedAt(), audit.createdBy());
    }
}
