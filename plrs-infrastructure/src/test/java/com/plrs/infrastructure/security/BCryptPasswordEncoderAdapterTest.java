package com.plrs.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.user.BCryptHash;
import org.junit.jupiter.api.Test;

class BCryptPasswordEncoderAdapterTest {

    private static final String VALID_PASSWORD = "Password01";

    private final BCryptPasswordEncoderAdapter encoder = new BCryptPasswordEncoderAdapter();

    @Test
    void encodeProducesValidBCryptHashAtCost12() {
        BCryptHash hash = encoder.encode(VALID_PASSWORD);

        String value = hash.value();
        assertThat(value).startsWith("$2");
        int cost = Integer.parseInt(value.substring(4, 6));
        assertThat(cost).isGreaterThanOrEqualTo(12);
    }

    @Test
    void encodeRejectsWeakPassword() {
        assertThatThrownBy(() -> encoder.encode("short01X"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void encodeRejectsNullPassword() {
        assertThatThrownBy(() -> encoder.encode(null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void matchesReturnsTrueForCorrectPassword() {
        BCryptHash hash = encoder.encode(VALID_PASSWORD);

        assertThat(encoder.matches(VALID_PASSWORD, hash)).isTrue();
    }

    @Test
    void matchesReturnsFalseForWrongPassword() {
        BCryptHash hash = encoder.encode(VALID_PASSWORD);

        assertThat(encoder.matches("Wrong12345", hash)).isFalse();
    }

    @Test
    void matchesReturnsFalseWhenRawIsNull() {
        BCryptHash hash = encoder.encode(VALID_PASSWORD);

        assertThat(encoder.matches(null, hash)).isFalse();
    }

    @Test
    void matchesReturnsFalseWhenHashIsNull() {
        assertThat(encoder.matches(VALID_PASSWORD, null)).isFalse();
    }

    @Test
    void encodedHashDiffersAcrossCallsForSamePassword() {
        BCryptHash first = encoder.encode(VALID_PASSWORD);
        BCryptHash second = encoder.encode(VALID_PASSWORD);

        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(encoder.matches(VALID_PASSWORD, first)).isTrue();
        assertThat(encoder.matches(VALID_PASSWORD, second)).isTrue();
    }
}
