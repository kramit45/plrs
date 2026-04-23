package com.plrs.domain.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

    @Test
    void acceptsMinimalValidPassword() {
        assertThatCode(() -> PasswordPolicy.validate("Password01"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void rejectsEmptyString() {
        assertThatThrownBy(() -> PasswordPolicy.validate(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @ParameterizedTest
    @ValueSource(strings = {"   ", "\t", " \n ", "          "})
    void rejectsWhitespaceOnly(String raw) {
        assertThatThrownBy(() -> PasswordPolicy.validate(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsShorterThanMinLength() {
        assertThatThrownBy(() -> PasswordPolicy.validate("short01X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(PasswordPolicy.MIN_LENGTH));
    }

    @Test
    void rejectsAllDigits() {
        assertThatThrownBy(() -> PasswordPolicy.validate("1234567890"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("letter");
    }

    @Test
    void rejectsAllLetters() {
        assertThatThrownBy(() -> PasswordPolicy.validate("abcdefghij"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digit");
    }

    @ParameterizedTest(name = "valid: \"{0}\"")
    @ValueSource(strings = {
        "Password01",
        "abcdefghi1",
        "aaaaaaaaa1Z",
        "Zz9Zz9Zz9Z",
        "mixedCase9_"
    })
    void acceptsVariedValidPasswords(String raw) {
        assertThatCode(() -> PasswordPolicy.validate(raw))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "rejects [{0}]")
    @CsvSource({
        "short01X",
        "abcdefghij",
        "1234567890",
        "'          '"
    })
    void rejectsVariedInvalidPasswords(String raw) {
        assertThatThrownBy(() -> PasswordPolicy.validate(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minLengthIsPubliclyExposedAndEnforced() {
        // Guards the acceptance criterion that infra/web can reference the
        // constant without duplication — the value here is read from the
        // policy itself, so changing MIN_LENGTH elsewhere keeps the test honest.
        int length = PasswordPolicy.MIN_LENGTH;
        String tooShort = "a".repeat(length - 2) + "1"; // length - 1 chars, has letter + digit

        assertThatThrownBy(() -> PasswordPolicy.validate(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(length));
    }
}
