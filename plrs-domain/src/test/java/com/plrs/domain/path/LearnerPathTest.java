package com.plrs.domain.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LearnerPathTest {

    private static final UserId USER = UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final TopicId TARGET = TopicId.of(42L);
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC);

    private static List<LearnerPathStep> twoSteps() {
        return List.of(
                LearnerPathStep.pending(1, ContentId.of(101L), false, "Prereq"),
                LearnerPathStep.pending(2, ContentId.of(102L), false, "Target-topic item"));
    }

    private static Map<TopicId, MasteryScore> startSnapshot() {
        return Map.of(TARGET, MasteryScore.ZERO);
    }

    @Test
    void newDraftStartsNotStartedWithGeneratedAt() {
        LearnerPath p =
                LearnerPath.newDraft(USER, TARGET, twoSteps(), startSnapshot(), FIXED);

        assertThat(p.id()).isEmpty();
        assertThat(p.status()).isEqualTo(LearnerPathStatus.NOT_STARTED);
        assertThat(p.generatedAt()).isEqualTo(Instant.parse("2026-04-26T10:00:00Z"));
        assertThat(p.startedAt()).isEmpty();
        assertThat(p.steps()).hasSize(2);
        assertThat(p.masteryEndSnapshot()).isEmpty();
    }

    @Test
    void startTransitionsFromNotStarted() {
        LearnerPath p = LearnerPath.newDraft(USER, TARGET, twoSteps(), startSnapshot(), FIXED);

        LearnerPath started = p.start(FIXED);

        assertThat(started.status()).isEqualTo(LearnerPathStatus.IN_PROGRESS);
        assertThat(started.startedAt()).contains(Instant.parse("2026-04-26T10:00:00Z"));
        // Original is unmodified.
        assertThat(p.status()).isEqualTo(LearnerPathStatus.NOT_STARTED);
    }

    @Test
    void startRejectsNonNotStartedSource() {
        LearnerPath p = LearnerPath.newDraft(USER, TARGET, twoSteps(), startSnapshot(), FIXED).start(FIXED);

        assertThatThrownBy(() -> p.start(FIXED))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("expected NOT_STARTED");
    }

    @Test
    void pauseAllowedOnlyFromInProgress() {
        LearnerPath draft = LearnerPath.newDraft(USER, TARGET, twoSteps(), startSnapshot(), FIXED);
        assertThatThrownBy(() -> draft.pause(FIXED))
                .isInstanceOf(DomainInvariantException.class);

        LearnerPath running = draft.start(FIXED);
        LearnerPath paused = running.pause(FIXED);

        assertThat(paused.status()).isEqualTo(LearnerPathStatus.PAUSED);
        assertThat(paused.pausedAt()).isPresent();
    }

    @Test
    void resumeAllowedOnlyFromPaused() {
        LearnerPath paused =
                LearnerPath.newDraft(USER, TARGET, twoSteps(), startSnapshot(), FIXED)
                        .start(FIXED)
                        .pause(FIXED);

        LearnerPath resumed = paused.resume(FIXED);

        assertThat(resumed.status()).isEqualTo(LearnerPathStatus.IN_PROGRESS);
        // pausedAt preserved for history.
        assertThat(resumed.pausedAt()).isPresent();
    }

    @Test
    void markStepDoneUpdatesStepAndAutoStarts() {
        LearnerPath draft = LearnerPath.newDraft(USER, TARGET, twoSteps(), startSnapshot(), FIXED);

        LearnerPath after = draft.markStepDone(1, FIXED);

        assertThat(after.status()).isEqualTo(LearnerPathStatus.IN_PROGRESS);
        assertThat(after.startedAt()).isPresent();
        assertThat(after.steps().get(0).status()).isEqualTo(StepStatus.DONE);
        assertThat(after.steps().get(0).completedAt()).isPresent();
        assertThat(after.steps().get(1).status()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void markStepDoneRejectsUnknownOrder() {
        LearnerPath p =
                LearnerPath.newDraft(USER, TARGET, twoSteps(), startSnapshot(), FIXED).start(FIXED);

        assertThatThrownBy(() -> p.markStepDone(99, FIXED))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("no step");
    }

    @Test
    void completeRequiresEndSnapshotAndInProgressOrReviewPending() {
        LearnerPath running =
                LearnerPath.newDraft(USER, TARGET, twoSteps(), startSnapshot(), FIXED).start(FIXED);

        Map<TopicId, MasteryScore> endSnap = Map.of(TARGET, MasteryScore.of(0.85));
        LearnerPath done = running.complete(endSnap, FIXED);

        assertThat(done.status()).isEqualTo(LearnerPathStatus.COMPLETED);
        assertThat(done.completedAt()).isPresent();
        assertThat(done.masteryEndSnapshot()).contains(endSnap);

        // Cannot complete twice.
        assertThatThrownBy(() -> done.complete(endSnap, FIXED))
                .isInstanceOf(DomainInvariantException.class);

        // Null endSnapshot rejected.
        assertThatThrownBy(() -> running.complete(null, FIXED))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void abandonAllowedFromAnyActiveStatus() {
        LearnerPath draft = LearnerPath.newDraft(USER, TARGET, twoSteps(), startSnapshot(), FIXED);
        assertThat(draft.abandon(FIXED).status()).isEqualTo(LearnerPathStatus.ABANDONED);

        LearnerPath paused = draft.start(FIXED).pause(FIXED);
        assertThat(paused.abandon(FIXED).status()).isEqualTo(LearnerPathStatus.ABANDONED);

        // Terminal cannot be re-abandoned.
        LearnerPath done =
                draft.start(FIXED).complete(Map.of(TARGET, MasteryScore.of(0.9)), FIXED);
        assertThatThrownBy(() -> done.abandon(FIXED))
                .isInstanceOf(DomainInvariantException.class);
    }

    @Test
    void supersededByStampsSuccessorAndStatus() {
        LearnerPath running =
                LearnerPath.newDraft(USER, TARGET, twoSteps(), startSnapshot(), FIXED).start(FIXED);
        PathId successor = PathId.of(7L);

        LearnerPath sup = running.supersededBy(successor, FIXED);

        assertThat(sup.status()).isEqualTo(LearnerPathStatus.SUPERSEDED);
        assertThat(sup.supersededBy()).contains(successor);
        assertThat(sup.supersededAt()).isPresent();
    }

    @Test
    void rehydrateRoundTripsAllFields() {
        Instant gen = Instant.parse("2026-01-01T00:00:00Z");
        LearnerPath rehy =
                LearnerPath.rehydrate(
                        PathId.of(99L),
                        USER,
                        TARGET,
                        LearnerPathStatus.COMPLETED,
                        gen,
                        Optional.of(gen.plusSeconds(60)),
                        Optional.empty(),
                        Optional.of(gen.plusSeconds(3600)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        twoSteps(),
                        startSnapshot(),
                        Optional.of(Map.of(TARGET, MasteryScore.of(0.9))));

        assertThat(rehy.id()).contains(PathId.of(99L));
        assertThat(rehy.status()).isEqualTo(LearnerPathStatus.COMPLETED);
        assertThat(rehy.masteryEndSnapshot()).isPresent();
    }

    @Test
    void illegalConstructorInputsRejected() {
        assertThatThrownBy(
                        () ->
                                LearnerPath.newDraft(
                                        null, TARGET, twoSteps(), startSnapshot(), FIXED))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(
                        () ->
                                LearnerPath.newDraft(
                                        USER, TARGET, twoSteps(), startSnapshot(), null))
                .isInstanceOf(DomainValidationException.class);
        // Duplicate stepOrder rejected by aggregate ctor.
        List<LearnerPathStep> dup =
                List.of(
                        LearnerPathStep.pending(1, ContentId.of(1L), false, "a"),
                        LearnerPathStep.pending(1, ContentId.of(2L), false, "b"));
        assertThatThrownBy(
                        () -> LearnerPath.newDraft(USER, TARGET, dup, startSnapshot(), FIXED))
                .isInstanceOf(DomainValidationException.class);
    }
}
