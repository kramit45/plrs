package com.plrs.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void valuesAreDeclaredInCanonicalOrder() {
        assertThat(Role.values())
                .containsExactly(Role.STUDENT, Role.INSTRUCTOR, Role.ADMIN);
    }

    @Test
    void fromNameReturnsMatchingRoleForEachValue() {
        assertThat(Role.fromName("STUDENT")).isEqualTo(Role.STUDENT);
        assertThat(Role.fromName("INSTRUCTOR")).isEqualTo(Role.INSTRUCTOR);
        assertThat(Role.fromName("ADMIN")).isEqualTo(Role.ADMIN);
    }

    @Test
    void fromNameIsCaseSensitive() {
        assertThatThrownBy(() -> Role.fromName("admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("admin")
                .hasMessageContaining("STUDENT")
                .hasMessageContaining("INSTRUCTOR")
                .hasMessageContaining("ADMIN");
    }

    @Test
    void fromNameRejectsNull() {
        assertThatThrownBy(() -> Role.fromName(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void fromNameRejectsUnknown() {
        assertThatThrownBy(() -> Role.fromName("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN")
                .hasMessageContaining("STUDENT")
                .hasMessageContaining("INSTRUCTOR")
                .hasMessageContaining("ADMIN");
    }
}
