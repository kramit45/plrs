package com.plrs.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserTest {

    private static final Instant T0 = Instant.parse("2026-04-23T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final String CREATOR = "system";

    private static final Email EMAIL = Email.of("kumar@example.com");
    private static final Email OTHER_EMAIL = Email.of("other@example.com");
    private static final String RAW_HASH =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";
    private static final BCryptHash HASH = BCryptHash.of(RAW_HASH);

    @Test
    void registerAssignsFreshIdDefaultRoleAndInitialAudit() {
        User user = User.register(EMAIL, HASH, CLOCK, CREATOR);

        assertThat(user.id()).isNotNull();
        assertThat(user.email()).isEqualTo(EMAIL);
        assertThat(user.passwordHash()).isEqualTo(HASH);
        assertThat(user.roles()).containsExactly(Role.STUDENT);
        assertThat(user.audit().createdAt()).isEqualTo(T0);
        assertThat(user.audit().updatedAt()).isEqualTo(T0);
        assertThat(user.audit().createdBy()).isEqualTo(CREATOR);
    }

    @Test
    void registerGivesEachUserADistinctId() {
        User a = User.register(EMAIL, HASH, CLOCK, CREATOR);
        User b = User.register(EMAIL, HASH, CLOCK, CREATOR);

        assertThat(a.id()).isNotEqualTo(b.id());
    }

    @Test
    void rehydrateRoundTripsAllFields() {
        UserId id = UserId.newId();
        Set<Role> roles = EnumSet.of(Role.STUDENT, Role.INSTRUCTOR);
        AuditFields audit = AuditFields.initial(CREATOR, T0).touchedAt(T0.plusSeconds(60));

        User user = User.rehydrate(id, EMAIL, HASH, roles, audit);

        assertThat(user.id()).isEqualTo(id);
        assertThat(user.email()).isEqualTo(EMAIL);
        assertThat(user.passwordHash()).isEqualTo(HASH);
        assertThat(user.roles()).containsExactlyInAnyOrder(Role.STUDENT, Role.INSTRUCTOR);
        assertThat(user.audit()).isEqualTo(audit);
    }

    @Test
    void rehydrateRejectsNullFields() {
        UserId id = UserId.newId();
        Set<Role> roles = Set.of(Role.STUDENT);
        AuditFields audit = AuditFields.initial(CREATOR, T0);

        assertThatThrownBy(() -> User.rehydrate(null, EMAIL, HASH, roles, audit))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> User.rehydrate(id, null, HASH, roles, audit))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("email");
        assertThatThrownBy(() -> User.rehydrate(id, EMAIL, null, roles, audit))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("passwordHash");
        assertThatThrownBy(() -> User.rehydrate(id, EMAIL, HASH, null, audit))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("roles");
        assertThatThrownBy(() -> User.rehydrate(id, EMAIL, HASH, roles, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("audit");
    }

    @Test
    void rehydrateRejectsEmptyRolesAsInvariantBreach() {
        UserId id = UserId.newId();
        AuditFields audit = AuditFields.initial(CREATOR, T0);

        assertThatThrownBy(() -> User.rehydrate(id, EMAIL, HASH, Set.of(), audit))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("at least one role");
    }

    @Test
    void rolesAccessorReturnsImmutableSet() {
        User user = User.register(EMAIL, HASH, CLOCK, CREATOR);

        assertThatThrownBy(() -> user.roles().add(Role.ADMIN))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mutatingTheInputRoleSetDoesNotAffectTheAggregate() {
        UserId id = UserId.newId();
        AuditFields audit = AuditFields.initial(CREATOR, T0);
        Set<Role> input = EnumSet.of(Role.STUDENT);

        User user = User.rehydrate(id, EMAIL, HASH, input, audit);
        input.add(Role.ADMIN);

        assertThat(user.roles()).containsExactly(Role.STUDENT);
    }

    @Test
    void equalsAndHashCodeAreBasedOnIdOnly() {
        UserId sharedId = UserId.newId();
        AuditFields audit = AuditFields.initial(CREATOR, T0);

        User a = User.rehydrate(sharedId, EMAIL, HASH, Set.of(Role.STUDENT), audit);
        User b = User.rehydrate(sharedId, OTHER_EMAIL, HASH, Set.of(Role.ADMIN), audit);
        User different = User.rehydrate(UserId.newId(), EMAIL, HASH, Set.of(Role.STUDENT), audit);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a User");
    }

    @Test
    void toStringExcludesPasswordHash() {
        User user = User.register(EMAIL, HASH, CLOCK, CREATOR);

        String rendered = user.toString();

        assertThat(rendered).doesNotContain(RAW_HASH);
        // Not even the masked form should leak into User.toString — the
        // aggregate has no business publishing hash state at all.
        assertThat(rendered).doesNotContain("$2b$");
        assertThat(rendered).contains(user.id().toString());
        assertThat(rendered).contains(EMAIL.value());
    }
}
