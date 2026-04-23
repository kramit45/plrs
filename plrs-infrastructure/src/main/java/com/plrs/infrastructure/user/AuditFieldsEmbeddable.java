package com.plrs.infrastructure.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA embeddable mirror of {@code com.plrs.domain.common.AuditFields}. The
 * domain type stays framework-free; this class carries the persistence
 * annotations so the three audit columns land on the owning entity's table
 * (see {@link UserJpaEntity}) with the widths and nullability declared by
 * {@code V2__users.sql}.
 *
 * <p>Kept deliberately asymmetric from the domain type: no validation here,
 * because the only path into an instance is the mapper (which sources data
 * from the already-validated domain object) or Hibernate (which reads
 * already-validated rows from the database CHECK constraint). Re-running
 * invariants would be pure duplication.
 *
 * <p>Traces to: §3.a (infra mirrors domain), §3.c (users schema).
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditFieldsEmbeddable {

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    public AuditFieldsEmbeddable(Instant createdAt, Instant updatedAt, String createdBy) {
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.createdBy = createdBy;
    }
}
