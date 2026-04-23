package com.plrs.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class BCryptHashTest {

    // 7-char prefix ($2b$12$) + 22-char salt + 31-char hash = 60 chars,
    // all within the BCrypt alphabet [./A-Za-z0-9].
    private static final String VALID_SALT = "abcdefghijklmnopqrstuv";
    private static final String VALID_HASH_TAIL = "wxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";
    private static final String VALID = "$2b$12$" + VALID_SALT + VALID_HASH_TAIL;

    @Test
    void acceptsWellFormedHashWithCost12() {
        BCryptHash hash = BCryptHash.of(VALID);

        assertThat(hash.value()).isEqualTo(VALID);
    }

    @Test
    void acceptsOtherLegalPrefixesAndHigherCost() {
        BCryptHash cost14 = BCryptHash.of("$2a$14$" + VALID_SALT + VALID_HASH_TAIL);
        BCryptHash cost31 = BCryptHash.of("$2y$31$" + VALID_SALT + VALID_HASH_TAIL);

        assertThat(cost14.value()).startsWith("$2a$14$");
        assertThat(cost31.value()).startsWith("$2y$31$");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> BCryptHash.of(null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("null");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> BCryptHash.of("   "))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsWrongPrefix() {
        String wrongPrefix = "$2c$12$" + VALID_SALT + VALID_HASH_TAIL;

        assertThatThrownBy(() -> BCryptHash.of(wrongPrefix))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("bcrypt");
    }

    @Test
    void rejectsWrongLength() {
        String truncated = VALID.substring(0, VALID.length() - 1); // 59 chars

        assertThatThrownBy(() -> BCryptHash.of(truncated))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("bcrypt");
    }

    @Test
    void rejectsCostBelowMinimum() {
        String cost10 = "$2b$10$" + VALID_SALT + VALID_HASH_TAIL;

        assertThatThrownBy(() -> BCryptHash.of(cost10))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("cost")
                .hasMessageContaining("12");
    }

    @Test
    void rejectsCharactersOutsideBcryptAlphabet() {
        String badSalt = "abcdefghijklmnopqrstu!"; // '!' not in [./A-Za-z0-9]
        String bad = "$2b$12$" + badSalt + VALID_HASH_TAIL;

        assertThatThrownBy(() -> BCryptHash.of(bad))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void toStringIsMaskedAndDoesNotLeakFullHash() {
        BCryptHash hash = BCryptHash.of(VALID);

        String masked = hash.toString();

        assertThat(masked).isEqualTo("$2b$12$***");
        assertThat(masked).doesNotContain(VALID_SALT);
        assertThat(masked).doesNotContain(VALID_HASH_TAIL);
    }

    @Test
    void equalityIsValueBased() {
        BCryptHash a = BCryptHash.of(VALID);
        BCryptHash b = BCryptHash.of(VALID);
        BCryptHash other = BCryptHash.of("$2b$12$" + VALID_SALT + VALID_HASH_TAIL.replace('1', '2'));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo(VALID); // different type
    }
}
