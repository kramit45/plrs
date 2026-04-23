package com.plrs.infrastructure.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.user.BCryptHash;
import com.plrs.domain.user.Email;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserMapperTest {

    private final UserMapper mapper = new UserMapper();

    private static final String RAW_HASH =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";
    private static final Instant T0 = Instant.parse("2026-04-23T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-04-23T11:00:00Z");

    @Test
    void toDomainRoundTripsAllFields() {
        UUID id = UUID.randomUUID();
        UserJpaEntity entity =
                new UserJpaEntity(
                        id,
                        "kumar@example.com",
                        RAW_HASH,
                        new HashSet<>(),
                        new AuditFieldsEmbeddable(T0, T1, "system"));
        entity.getRoles()
                .add(new UserRoleJpaEntity(new UserRoleId(id, Role.STUDENT), entity, T0));
        entity.getRoles()
                .add(new UserRoleJpaEntity(new UserRoleId(id, Role.INSTRUCTOR), entity, T1));

        User user = mapper.toDomain(entity);

        assertThat(user.id().value()).isEqualTo(id);
        assertThat(user.email().value()).isEqualTo("kumar@example.com");
        assertThat(user.passwordHash().value()).isEqualTo(RAW_HASH);
        assertThat(user.roles()).containsExactlyInAnyOrder(Role.STUDENT, Role.INSTRUCTOR);
        assertThat(user.audit().createdAt()).isEqualTo(T0);
        assertThat(user.audit().updatedAt()).isEqualTo(T1);
        assertThat(user.audit().createdBy()).isEqualTo("system");
    }

    @Test
    void toEntityRoundTripsAllFields() {
        User user =
                User.rehydrate(
                        UserId.of(UUID.randomUUID()),
                        Email.of("kumar@example.com"),
                        BCryptHash.of(RAW_HASH),
                        Set.of(Role.STUDENT, Role.ADMIN),
                        AuditFields.initial("system", T0).touchedAt(T1));

        UserJpaEntity entity = mapper.toEntity(user);

        assertThat(entity.getId()).isEqualTo(user.id().value());
        assertThat(entity.getEmail()).isEqualTo(user.email().value());
        assertThat(entity.getPasswordHash()).isEqualTo(RAW_HASH);
        assertThat(entity.getAudit().getCreatedAt()).isEqualTo(T0);
        assertThat(entity.getAudit().getUpdatedAt()).isEqualTo(T1);
        assertThat(entity.getAudit().getCreatedBy()).isEqualTo("system");
    }

    @Test
    void toEntityProducesOneJoinRowPerRoleLinkedToTheParent() {
        User user =
                User.register(
                        Email.of("kumar@example.com"),
                        BCryptHash.of(RAW_HASH),
                        Clock.fixed(T0, ZoneOffset.UTC),
                        "system")
                        .assignRole(Role.INSTRUCTOR, Clock.fixed(T1, ZoneOffset.UTC))
                        .assignRole(Role.ADMIN, Clock.fixed(T1, ZoneOffset.UTC));

        UserJpaEntity entity = mapper.toEntity(user);

        assertThat(entity.getRoles()).hasSize(3);
        assertThat(entity.getRoles())
                .allSatisfy(
                        role -> {
                            assertThat(role.getUser()).isSameAs(entity);
                            assertThat(role.getId().getUserId()).isEqualTo(entity.getId());
                            assertThat(role.getAssignedAt()).isEqualTo(user.audit().updatedAt());
                        });
        assertThat(
                        entity.getRoles().stream()
                                .map(r -> r.getId().getRole())
                                .toList())
                .containsExactlyInAnyOrder(Role.STUDENT, Role.INSTRUCTOR, Role.ADMIN);
    }

    @Test
    void domainRoundTripEqualsOriginal() {
        User original =
                User.rehydrate(
                        UserId.of(UUID.randomUUID()),
                        Email.of("kumar@example.com"),
                        BCryptHash.of(RAW_HASH),
                        Set.of(Role.STUDENT, Role.INSTRUCTOR),
                        AuditFields.initial("system", T0).touchedAt(T1));

        User roundTripped = mapper.toDomain(mapper.toEntity(original));

        // User equality is identity-based, so this guards the id only; the
        // follow-up assertions verify every other field also survived.
        assertThat(roundTripped).isEqualTo(original);
        assertThat(roundTripped.email()).isEqualTo(original.email());
        assertThat(roundTripped.passwordHash()).isEqualTo(original.passwordHash());
        assertThat(roundTripped.roles()).isEqualTo(original.roles());
        assertThat(roundTripped.audit()).isEqualTo(original.audit());
    }

    @Test
    void toDomainReturnsNullOnNullEntity() {
        assertThat(mapper.toDomain(null)).isNull();
    }

    @Test
    void toEntityReturnsNullOnNullUser() {
        assertThat(mapper.toEntity(null)).isNull();
    }
}
