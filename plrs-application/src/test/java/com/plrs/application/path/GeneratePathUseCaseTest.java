package com.plrs.application.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathDraft;
import com.plrs.domain.path.LearnerPathRepository;
import com.plrs.domain.path.LearnerPathStatus;
import com.plrs.domain.path.LearnerPathStep;
import com.plrs.domain.path.PathId;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GeneratePathUseCaseTest {

    private static final UserId USER = UserId.of(UUID.randomUUID());
    private static final TopicId TARGET = TopicId.of(7L);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC);

    @Mock private PathPlanner planner;
    @Mock private LearnerPathRepository repo;

    private LearnerPathDraft sampleDraft() {
        return new LearnerPathDraft(
                USER,
                TARGET,
                List.of(LearnerPathStep.pending(1, ContentId.of(1L), false, "x")),
                Map.of(TARGET, MasteryScore.ZERO));
    }

    private LearnerPath persisted(long id, LearnerPathStatus status) {
        return LearnerPath.rehydrate(
                PathId.of(id),
                USER,
                TARGET,
                status,
                Instant.parse("2026-04-25T00:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(LearnerPathStep.pending(1, ContentId.of(1L), false, "x")),
                Map.of(TARGET, MasteryScore.ZERO),
                Optional.empty());
    }

    @Test
    void generatesAndPersistsWhenNoPriorActive() {
        when(planner.plan(USER, TARGET)).thenReturn(sampleDraft());
        when(repo.findActiveByUserAndTarget(USER, TARGET)).thenReturn(Optional.empty());
        when(repo.save(any())).thenReturn(persisted(42L, LearnerPathStatus.NOT_STARTED));

        PathId out = new GeneratePathUseCase(planner, repo, CLOCK).handle(USER, TARGET);

        assertThat(out).isEqualTo(PathId.of(42L));
        verify(repo, never()).update(any());
    }

    @Test
    void supersedesPriorActiveBeforeSaving() {
        LearnerPath prior = persisted(10L, LearnerPathStatus.IN_PROGRESS);
        LearnerPath savedNew = persisted(99L, LearnerPathStatus.NOT_STARTED);
        when(planner.plan(USER, TARGET)).thenReturn(sampleDraft());
        when(repo.findActiveByUserAndTarget(USER, TARGET))
                .thenReturn(Optional.of(prior));
        when(repo.save(any())).thenReturn(savedNew);
        // Refresh after save: still SUPERSEDED with placeholder superseded_by.
        LearnerPath supersededWithPlaceholder = prior.supersededBy(PathId.of(10L), CLOCK);
        when(repo.findById(PathId.of(10L)))
                .thenReturn(Optional.of(supersededWithPlaceholder));
        when(repo.update(any())).thenAnswer(inv -> inv.getArgument(0));

        PathId out = new GeneratePathUseCase(planner, repo, CLOCK).handle(USER, TARGET);

        assertThat(out).isEqualTo(PathId.of(99L));
        // Two updates: 1) supersede with placeholder, 2) back-patch real successor id.
        ArgumentCaptor<LearnerPath> cap = ArgumentCaptor.forClass(LearnerPath.class);
        verify(repo, times(2)).update(cap.capture());
        // The second update carries the new path's id as supersededBy.
        assertThat(cap.getAllValues().get(1).supersededBy()).contains(PathId.of(99L));
        assertThat(cap.getAllValues().get(1).status())
                .isEqualTo(LearnerPathStatus.SUPERSEDED);
    }
}
