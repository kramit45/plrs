package com.plrs.domain.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DomainValidationExceptionTest {

    @Test
    void messageConstructorStoresMessage() {
        DomainValidationException e = new DomainValidationException("bad input");

        assertThat(e.getMessage()).isEqualTo("bad input");
        assertThat(e.getCause()).isNull();
    }

    @Test
    void messageAndCauseConstructorStoresBoth() {
        Throwable cause = new IllegalStateException("root");

        DomainValidationException e = new DomainValidationException("wrapped", cause);

        assertThat(e.getMessage()).isEqualTo("wrapped");
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void isARuntimeException() {
        DomainValidationException e = new DomainValidationException("x");

        assertThat(e).isInstanceOf(RuntimeException.class);
    }

    @Test
    void domainInvariantExceptionIsADomainValidationException() {
        DomainInvariantException e = new DomainInvariantException("invariant broken");

        assertThat(e)
                .isInstanceOf(DomainValidationException.class)
                .isInstanceOf(RuntimeException.class);
        assertThat(e.getMessage()).isEqualTo("invariant broken");
    }

    @Test
    void domainInvariantExceptionPreservesCause() {
        Throwable cause = new IllegalStateException("root");

        DomainInvariantException e = new DomainInvariantException("wrapped", cause);

        assertThat(e.getCause()).isSameAs(cause);
    }
}
