package com.plrs.infrastructure.user;

import com.plrs.domain.user.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Composite primary key for {@link UserRoleJpaEntity}, mirroring the
 * {@code user_roles_pk} constraint declared in {@code V3__user_roles.sql}.
 * Implements {@link Serializable} and value-based equality/hash code as
 * required for a {@code @EmbeddedId}.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRoleId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Role role;

    public UserRoleId(UUID userId, Role role) {
        this.userId = userId;
        this.role = role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserRoleId other)) {
            return false;
        }
        return Objects.equals(userId, other.userId) && role == other.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, role);
    }
}
