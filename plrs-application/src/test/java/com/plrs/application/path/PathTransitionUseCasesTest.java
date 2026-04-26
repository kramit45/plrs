package com.plrs.application.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.mastery.UserSkill;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathRepository;
import com.plrs.domain.path.LearnerPathStatus;
import com.plrs.domain.path.LearnerPathStep;
import com.plrs.domain.path.PathId;
import com.plrs.domain.path.StepStatus;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PathTransitionUseCasesTest {

    private static final UserId USER = UserId.of(UUID.randomUUID());
    private static final TopicId TARGET = TopicId.of(7L);
    private static final PathId PATH = PathId.of(99L);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC);

    @Mock private LearnerPathRepository repo;
    @Mock private UserSkillRepository skills;

    private LearnerPath persistedDraft() {
        return LearnerPath.rehydrate(
                PATH,
                USER,
                TARGET,
                LearnerPathStatus.NOT_STARTED,
                Instant.parse("2026-04-26T09:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(
                        LearnerPathStep.pending(1, ContentId.of(101L), false, "step"),
                        LearnerPathStep.pending(2, ContentId.of(102L), false, "step")),
                Map.of(TARGET, MasteryScore.ZERO),
                Optional.empty());
    }

    @Test
    void startPersistsInProgress() {
        when(repo.findById(PATH)).thenReturn(Optional.of(persistedDraft()));
        when(repo.update(any())).thenAnswer(inv -> inv.getArgument(0));

        new StartPathUseCase(repo, CLOCK).handle(PATH);

        ArgumentCaptor<LearnerPath> cap = ArgumentCaptor.forClass(LearnerPath.class);
        verify(repo).update(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(LearnerPathStatus.IN_PROGRESS);
        assertThat(cap.getValue().startedAt()).isPresent();
    }

    @Test
    void pauseRejectsFromNotStarted() {
        when(repo.findById(PATH)).thenReturn(Optional.of(persistedDraft()));

        assertThatThrownBy(() -> new PausePathUseCase(repo, CLOCK).handle(PATH))
                .isInstanceOf(DomainInvariantException.class);
        verify(repo, never()).update(any());
    }

    @Test
    void resumeFromPaused() {
        LearnerPath paused = persistedDraft().start(CLOCK).pause(CLOCK);
        when(repo.findById(PATH)).thenReturn(Optional.of(paused));
        when(repo.update(any())).thenAnswer(inv -> inv.getArgument(0));

        new ResumePathUseCase(repo, CLOCK).handle(PATH);

        ArgumentCaptor<LearnerPath> cap = ArgumentCaptor.forClass(LearnerPath.class);
        verify(repo).update(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(LearnerPathStatus.IN_PROGRESS);
    }

    @Test
    void abandonAllowedFromAnyActive() {
        when(repo.findById(PATH)).thenReturn(Optional.of(persistedDraft()));
        when(repo.update(any())).thenAnswer(inv -> inv.getArgument(0));

        new AbandonPathUseCase(repo, CLOCK).handle(PATH);

        ArgumentCaptor<LearnerPath> cap = ArgumentCaptor.forClass(LearnerPath.class);
        verify(repo).update(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(LearnerPathStatus.ABANDONED);
    }

    @Test
    void markStepDoneAdvancesPathButNotComplete() {
        when(repo.findById(PATH)).thenReturn(Optional.of(persistedDraft()));
        when(repo.update(any())).thenAnswer(inv -> inv.getArgument(0));

        new MarkPathStepDoneUseCase(repo, skills, CLOCK).handle(PATH, 1);

        ArgumentCaptor<LearnerPath> cap = ArgumentCaptor.forClass(LearnerPath.class);
        verify(repo).update(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(LearnerPathStatus.IN_PROGRESS);
        assertThat(cap.getValue().steps().get(0).status()).isEqualTo(StepStatus.DONE);
    }

    @Test
    void markStepDoneTriggersCompleteWhenAllDone() {
        // Take the draft, mark step 1 done first via another call to set up state.
        LearnerPath stepOneDone = persistedDraft().markStepDone(1, CLOCK);
        when(repo.findById(PATH)).thenReturn(Optional.of(stepOneDone));
        when(repo.update(any())).thenAnswer(inv -> inv.getArgument(0));
        when(skills.findByUser(USER))
                .thenReturn(
                        List.of(
                                UserSkill.rehydrate(
                                        USER,
                                        TARGET,
                                        MasteryScore.of(0.85),
                                        new BigDecimal("0.500"),
                                        Instant.parse("2026-04-26T09:30:00Z"))));

        new MarkPathStepDoneUseCase(repo, skills, CLOCK).handle(PATH, 2);

        ArgumentCaptor<LearnerPath> cap = ArgumentCaptor.forClass(LearnerPath.class);
        verify(repo, times(1)).update(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(LearnerPathStatus.COMPLETED);
        assertThat(cap.getValue().masteryEndSnapshot())
                .isPresent()
                .get()
                .extracting(m -> m.get(TARGET))
                .isEqualTo(MasteryScore.of(0.85));
    }
}
