package com.plrs.domain.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuditFieldsTest {

    private static final String ACTOR = "system";
    private static final Instant T0 = Instant.parse("2026-04-23T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-04-23T11:00:00Z");

    @Test
    void initialPinsBothTimestampsToTheSameInstant() {
        AuditFields audit = AuditFields.initial(ACTOR, T0);

        assertThat(audit.createdAt()).isEqualTo(T0);
        assertThat(audit.updatedAt()).isEqualTo(T0);
        assertThat(audit.createdBy()).isEqualTo(ACTOR);
    }

    @Test
    void touchedAtProducesANewInstanceWithLaterUpdatedAt() {
        AuditFields original = AuditFields.initial(ACTOR, T0);

        AuditFields touched = original.touchedAt(T1);

        assertThat(touched.createdAt()).isEqualTo(T0);
        assertThat(touched.updatedAt()).isEqualTo(T1);
        assertThat(touched.createdBy()).isEqualTo(ACTOR);
    }

    @Test
    void touchedAtDoesNotMutateTheOriginal() {
        AuditFields original = AuditFields.initial(ACTOR, T0);

        AuditFields touched = original.touchedAt(T1);

        assertThat(original.updatedAt()).isEqualTo(T0);
        assertThat(touched).isNotSameAs(original);
    }

    @Test
    void rejectsNullCreatedBy() {
        assertThatThrownBy(() -> AuditFields.initial(null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("createdBy")
                .hasMessageContaining("null");
    }

    @Test
    void rejectsBlankCreatedBy() {
        assertThatThrownBy(() -> AuditFields.initial("   ", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("createdBy")
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsNullInstantOnInitial() {
        assertThatThrownBy(() -> AuditFields.initial(ACTOR, (Instant) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void touchedAtRejectsNullInstant() {
        AuditFields audit = AuditFields.initial(ACTOR, T0);

        assertThatThrownBy(() -> audit.touchedAt(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updatedAt")
                .hasMessageContaining("null");
    }

    @Test
    void touchedAtRejectsInstantBeforeCreatedAt() {
        AuditFields audit = AuditFields.initial(ACTOR, T1);
        Instant earlier = T0;

        assertThatThrownBy(() -> audit.touchedAt(earlier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updatedAt")
                .hasMessageContaining("before");
    }

    @Test
    void clockOverloadForInitialIsDeterministic() {
        Clock fixed = Clock.fixed(T0, ZoneOffset.UTC);

        AuditFields audit = AuditFields.initial(ACTOR, fixed);

        assertThat(audit.createdAt()).isEqualTo(T0);
        assertThat(audit.updatedAt()).isEqualTo(T0);
    }

    @Test
    void clockOverloadForTouchAdvancesUpdatedAt() {
        AuditFields original = AuditFields.initial(ACTOR, T0);
        Clock later = Clock.fixed(T1, ZoneOffset.UTC);

        AuditFields touched = original.touched(later);

        assertThat(touched.updatedAt()).isEqualTo(T1);
        assertThat(touched.createdAt()).isEqualTo(T0);
    }

    @Test
    void equalityAndHashCodeAreValueBased() {
        AuditFields a = AuditFields.initial(ACTOR, T0);
        AuditFields b = AuditFields.initial(ACTOR, T0);
        AuditFields different = a.touchedAt(T1);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not AuditFields");
    }
}
