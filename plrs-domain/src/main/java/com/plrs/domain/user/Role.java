package com.plrs.domain.user;

import com.plrs.domain.common.DomainValidationException;
import java.util.Arrays;

/**
 * The three roles PLRS assigns to a user. Roles are <em>additive</em> — a
 * single user may hold any combination of them — and composition is handled
 * on {@code User} (step 24) rather than by a hierarchy encoded here. The
 * declared order (STUDENT, INSTRUCTOR, ADMIN) is load-bearing: downstream
 * persistence (DB {@code CHECK} constraints, enum-to-ordinal columns) and
 * UI code can rely on it, so do not reorder without auditing every caller.
 *
 * <p>Traces to: §7 (additive role model), §2.c (roles STUDENT, INSTRUCTOR, ADMIN).
 */
public enum Role {
    STUDENT,
    INSTRUCTOR,
    ADMIN;

    /**
     * Parses a role name using a case-sensitive match. The case-sensitivity
     * is deliberate — it forces callers and serialisation formats to agree
     * on the canonical spelling rather than silently accepting "admin" and
     * "Admin" as synonyms, which would scatter normalisation logic across
     * the codebase.
     *
     * @throws DomainValidationException when {@code name} is null or does
     *     not match any role; the message lists the valid values
     */
    public static Role fromName(String name) {
        if (name == null) {
            throw new DomainValidationException(
                    "Role name must not be null; expected one of " + Arrays.toString(values()));
        }
        for (Role role : values()) {
            if (role.name().equals(name)) {
                return role;
            }
        }
        throw new DomainValidationException(
                "Unknown role: '" + name + "'; expected one of " + Arrays.toString(values()));
    }
}
