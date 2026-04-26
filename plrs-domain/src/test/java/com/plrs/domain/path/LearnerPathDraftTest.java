package com.plrs.domain.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LearnerPathDraftTest {

    private static final UserId USER = UserId.of(UUID.randomUUID());
    private static final TopicId TARGET = TopicId.of(7L);

    @Test
    void roundTripsAllFields() {
        List<LearnerPathStep> steps =
                List.of(LearnerPathStep.pending(1, ContentId.of(99L), false, "Target"));
        Map<TopicId, MasteryScore> snap = Map.of(TARGET, MasteryScore.ZERO);

        LearnerPathDraft draft = new LearnerPathDraft(USER, TARGET, steps, snap);

        assertThat(draft.userId()).isEqualTo(USER);
        assertThat(draft.targetTopicId()).isEqualTo(TARGET);
        assertThat(draft.steps()).hasSize(1);
        assertThat(draft.masteryStartSnapshot()).containsEntry(TARGET, MasteryScore.ZERO);
    }

    @Test
    void rejectsDuplicateStepOrder() {
        List<LearnerPathStep> dup =
                List.of(
                        LearnerPathStep.pending(1, ContentId.of(1L), false, "a"),
                        LearnerPathStep.pending(1, ContentId.of(2L), false, "b"));
        assertThatThrownBy(() -> new LearnerPathDraft(USER, TARGET, dup, Map.of()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("unique stepOrder");
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> new LearnerPathDraft(null, TARGET, List.of(), Map.of()))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new LearnerPathDraft(USER, null, List.of(), Map.of()))
                .isInstanceOf(DomainValidationException.class);
    }
}
