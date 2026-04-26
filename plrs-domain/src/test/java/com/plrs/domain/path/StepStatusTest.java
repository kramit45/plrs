package com.plrs.domain.path;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StepStatusTest {

    @Test
    void hasFourDeclaredValuesInOrder() {
        assertThat(StepStatus.values())
                .containsExactly(
                        StepStatus.PENDING,
                        StepStatus.IN_PROGRESS,
                        StepStatus.DONE,
                        StepStatus.SKIPPED);
    }

    @Test
    void valueOfRoundTripsAllNames() {
        for (StepStatus s : StepStatus.values()) {
            assertThat(StepStatus.valueOf(s.name())).isSameAs(s);
        }
    }
}
