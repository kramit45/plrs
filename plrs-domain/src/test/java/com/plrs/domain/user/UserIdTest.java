package com.plrs.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserIdTest {

    private static final String CANONICAL = "11111111-2222-3333-4444-555555555555";

    @Test
    void newIdReturnsNonNullDistinctInstances() {
        UserId a = UserId.newId();
        UserId b = UserId.newId();

        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.value()).isNotNull().isNotEqualTo(b.value());
    }

    @Test
    void ofUuidRoundTripsThroughValue() {
        UUID raw = UUID.fromString(CANONICAL);

        UserId id = UserId.of(raw);

        assertThat(id.value()).isEqualTo(raw);
    }

    @Test
    void ofStringParsesValidUuid() {
        UserId id = UserId.of(CANONICAL);

        assertThat(id.value()).isEqualTo(UUID.fromString(CANONICAL));
    }

    @Test
    void ofRejectsNullUuid() {
        assertThatThrownBy(() -> UserId.of((UUID) null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void ofRejectsInvalidString() {
        assertThatThrownBy(() -> UserId.of("not-a-uuid"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void ofRejectsNullString() {
        assertThatThrownBy(() -> UserId.of((String) null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        UserId a = UserId.of(CANONICAL);
        UserId b = UserId.of(UUID.fromString(CANONICAL));
        UserId other = UserId.newId();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a UserId");
    }

    @Test
    void toStringReturnsCanonicalUuid() {
        UserId id = UserId.of(CANONICAL);

        assertThat(id.toString()).isEqualTo(CANONICAL);
    }
}
