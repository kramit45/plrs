package com.plrs.domain.path;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LearnerPathStatusTest {

    @ParameterizedTest
    @EnumSource(
            value = LearnerPathStatus.class,
            names = {"NOT_STARTED", "IN_PROGRESS", "PAUSED", "REVIEW_PENDING"})
    void activeStatesReportActive(LearnerPathStatus status) {
        assertThat(status.isActive()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = LearnerPathStatus.class,
            names = {"COMPLETED", "ABANDONED", "SUPERSEDED"})
    void terminalStatesReportInactive(LearnerPathStatus status) {
        assertThat(status.isActive()).isFalse();
    }

    @org.junit.jupiter.api.Test
    void allSevenValuesAreDeclared() {
        assertThat(LearnerPathStatus.values()).hasSize(7);
    }
}
