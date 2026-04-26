package com.plrs.domain.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LearnerPathStepTest {

    @Test
    void pendingFactoryYieldsPendingStepWithoutTimestamps() {
        LearnerPathStep s = LearnerPathStep.pending(1, ContentId.of(7L), false, "Prereq");

        assertThat(s.stepOrder()).isEqualTo(1);
        assertThat(s.contentId()).isEqualTo(ContentId.of(7L));
        assertThat(s.status()).isEqualTo(StepStatus.PENDING);
        assertThat(s.addedAsReview()).isFalse();
        assertThat(s.startedAt()).isEmpty();
        assertThat(s.completedAt()).isEmpty();
    }

    @Test
    void rejectsNonPositiveOrder() {
        assertThatThrownBy(() -> LearnerPathStep.pending(0, ContentId.of(1L), false, "x"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsBlankReason() {
        assertThatThrownBy(() -> LearnerPathStep.pending(1, ContentId.of(1L), false, ""))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsOverlongReason() {
        String tooLong = "x".repeat(LearnerPathStep.MAX_REASON_LENGTH + 1);
        assertThatThrownBy(() -> LearnerPathStep.pending(1, ContentId.of(1L), false, tooLong))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void markDonePopulatesTimestampsAndStatus() {
        LearnerPathStep pending = LearnerPathStep.pending(1, ContentId.of(1L), false, "x");
        Instant when = Instant.parse("2026-04-26T11:00:00Z");

        LearnerPathStep done = pending.markDone(when);

        assertThat(done.status()).isEqualTo(StepStatus.DONE);
        assertThat(done.startedAt()).contains(when);
        assertThat(done.completedAt()).contains(when);
    }
}
