package com.plrs.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailTest {

    @Test
    void acceptsSimpleValidAddress() {
        Email email = Email.of("kumar@example.com");

        assertThat(email.value()).isEqualTo("kumar@example.com");
    }

    @Test
    void acceptsComplexValidAddress() {
        Email email = Email.of("a.b+c@sub.example.co.in");

        assertThat(email.value()).isEqualTo("a.b+c@sub.example.co.in");
    }

    @Test
    void normalisesWhitespaceAndCase() {
        Email email = Email.of("  Kumar@Example.COM  ");

        assertThat(email.value()).isEqualTo("kumar@example.com");
        assertThat(email.toString()).isEqualTo("kumar@example.com");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Email.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> Email.of("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsMissingAtSign() {
        assertThatThrownBy(() -> Email.of("no-at-sign"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void rejectsMissingTopLevelDomain() {
        assertThatThrownBy(() -> Email.of("missing@tld"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void rejectsMissingLocalPart() {
        assertThatThrownBy(() -> Email.of("@missing-local.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void rejectsLongerThan254Characters() {
        String localPart = "a".repeat(250);
        String tooLong = localPart + "@ex.co"; // 250 + 6 = 256 chars

        assertThatThrownBy(() -> Email.of(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("254");
    }

    @Test
    void equalityIsBasedOnNormalisedValue() {
        Email lower = Email.of("a@b.com");
        Email upper = Email.of("A@B.COM");
        Email other = Email.of("c@d.com");

        assertThat(lower).isEqualTo(upper).hasSameHashCodeAs(upper);
        assertThat(lower).isNotEqualTo(other);
        assertThat(lower).isNotEqualTo(null);
        assertThat(lower).isNotEqualTo("a@b.com"); // different type
    }

    /**
     * Documents a known gap: the pragmatic regex accepts a leading dot in the
     * local part. Strict RFC 5322 would reject this, but callers do not rely
     * on that guarantee. See the Javadoc on {@link Email} for the tradeoff.
     */
    @Test
    void pragmaticRegexAcceptsLeadingDotLocalPart() {
        Email email = Email.of(".leading.dot@example.com");

        assertThat(email.value()).isEqualTo(".leading.dot@example.com");
    }
}
