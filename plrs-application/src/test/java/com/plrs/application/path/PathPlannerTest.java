package com.plrs.application.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.mastery.UserSkill;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.path.LearnerPathDraft;
import com.plrs.domain.path.LearnerPathStep;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PathPlannerTest {

    private static final UserId USER = UserId.of(UUID.randomUUID());
    private static final TopicId TOPIC_TARGET = TopicId.of(10L);
    private static final TopicId TOPIC_PREREQ = TopicId.of(20L);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC);

    @Mock private ContentRepository contentRepo;
    @Mock private PrerequisiteRepository prereqRepo;
    @Mock private UserSkillRepository skillRepo;

    private PathPlanner planner() {
        return new PathPlanner(contentRepo, prereqRepo, skillRepo, CLOCK);
    }

    private Content content(long id, TopicId topicId, String title) {
        return Content.rehydrate(
                ContentId.of(id),
                topicId,
                title,
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                5,
                "https://x.y/" + id,
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("test", CLOCK));
    }

    private PrerequisiteEdge edge(long contentId, long prereqId) {
        return new PrerequisiteEdge(
                ContentId.of(contentId),
                ContentId.of(prereqId),
                Instant.parse("2026-01-01T00:00:00Z"),
                Optional.empty());
    }

    @Test
    void emptyTargetTopicThrows() {
        when(contentRepo.findByTopicId(TOPIC_TARGET)).thenReturn(List.of());
        when(skillRepo.findByUser(USER)).thenReturn(List.of());

        assertThatThrownBy(() -> planner().plan(USER, TOPIC_TARGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no content");
    }

    @Test
    void targetWithNoPrereqsReturnsJustTargetItems() {
        Content t1 = content(1, TOPIC_TARGET, "Target1");
        Content t2 = content(2, TOPIC_TARGET, "Target2");
        when(skillRepo.findByUser(USER)).thenReturn(List.of());
        when(contentRepo.findByTopicId(TOPIC_TARGET)).thenReturn(List.of(t1, t2));
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(1L))).thenReturn(List.of());
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(2L))).thenReturn(List.of());
        when(contentRepo.findById(ContentId.of(1L))).thenReturn(Optional.of(t1));
        when(contentRepo.findById(ContentId.of(2L))).thenReturn(Optional.of(t2));

        LearnerPathDraft draft = planner().plan(USER, TOPIC_TARGET);

        assertThat(draft.steps())
                .extracting(LearnerPathStep::contentId)
                .containsExactlyInAnyOrder(ContentId.of(1L), ContentId.of(2L));
        assertThat(draft.steps())
                .allSatisfy(s -> assertThat(s.reasonInPath()).isEqualTo("Target-topic item"));
    }

    @Test
    void prereqAppearsBeforeDependent_FR32_invariant() {
        // Target item 1 depends on prereq 100 (different topic).
        Content t1 = content(1, TOPIC_TARGET, "Target");
        Content p100 = content(100, TOPIC_PREREQ, "Prereq");
        when(skillRepo.findByUser(USER)).thenReturn(List.of());
        when(contentRepo.findByTopicId(TOPIC_TARGET)).thenReturn(List.of(t1));
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(1L)))
                .thenReturn(List.of(edge(1L, 100L)));
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(100L))).thenReturn(List.of());
        when(contentRepo.findById(ContentId.of(1L))).thenReturn(Optional.of(t1));
        when(contentRepo.findById(ContentId.of(100L))).thenReturn(Optional.of(p100));

        LearnerPathDraft draft = planner().plan(USER, TOPIC_TARGET);

        // Indexes by content id for the assertion.
        Map<ContentId, Integer> orderByContent = new HashMap<>();
        for (LearnerPathStep s : draft.steps()) {
            orderByContent.put(s.contentId(), s.stepOrder());
        }
        assertThat(orderByContent.get(ContentId.of(100L)))
                .as("prereq must come before its dependent")
                .isLessThan(orderByContent.get(ContentId.of(1L)));
        // Reasons distinguish prereqs from targets.
        LearnerPathStep prereq =
                draft.steps().stream()
                        .filter(s -> s.contentId().equals(ContentId.of(100L)))
                        .findFirst()
                        .orElseThrow();
        assertThat(prereq.reasonInPath()).isEqualTo("Prerequisite for target topic");
    }

    @Test
    void masteredPrereqsArePruned() {
        Content t1 = content(1, TOPIC_TARGET, "Target");
        Content p100 = content(100, TOPIC_PREREQ, "Prereq");
        // Learner has 0.9 mastery on the prereq's topic — drop the prereq.
        when(skillRepo.findByUser(USER))
                .thenReturn(
                        List.of(
                                UserSkill.rehydrate(
                                        USER,
                                        TOPIC_PREREQ,
                                        MasteryScore.of(0.9),
                                        new BigDecimal("0.500"),
                                        Instant.parse("2026-04-01T00:00:00Z"))));
        when(contentRepo.findByTopicId(TOPIC_TARGET)).thenReturn(List.of(t1));
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(1L)))
                .thenReturn(List.of(edge(1L, 100L)));
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(100L))).thenReturn(List.of());
        when(contentRepo.findById(ContentId.of(1L))).thenReturn(Optional.of(t1));
        when(contentRepo.findById(ContentId.of(100L))).thenReturn(Optional.of(p100));

        LearnerPathDraft draft = planner().plan(USER, TOPIC_TARGET);

        assertThat(draft.steps())
                .extracting(LearnerPathStep::contentId)
                .containsExactly(ContentId.of(1L));
    }

    @Test
    void cycleDetectionThrows() {
        Content a = content(1, TOPIC_TARGET, "A");
        Content b = content(2, TOPIC_TARGET, "B");
        when(skillRepo.findByUser(USER)).thenReturn(List.of());
        when(contentRepo.findByTopicId(TOPIC_TARGET)).thenReturn(List.of(a, b));
        // a → b → a cycle (within the target topic so both stay in the
        // pruned set).
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(1L)))
                .thenReturn(List.of(edge(1L, 2L)));
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(2L)))
                .thenReturn(List.of(edge(2L, 1L)));
        when(contentRepo.findById(ContentId.of(1L))).thenReturn(Optional.of(a));
        when(contentRepo.findById(ContentId.of(2L))).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> planner().plan(USER, TOPIC_TARGET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void masteryStartSnapshotCaptured() {
        Content t1 = content(1, TOPIC_TARGET, "T");
        when(skillRepo.findByUser(USER))
                .thenReturn(
                        List.of(
                                UserSkill.rehydrate(
                                        USER,
                                        TOPIC_PREREQ,
                                        MasteryScore.of(0.55),
                                        new BigDecimal("0.300"),
                                        Instant.parse("2026-04-01T00:00:00Z"))));
        when(contentRepo.findByTopicId(TOPIC_TARGET)).thenReturn(List.of(t1));
        lenient().when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(1L))).thenReturn(List.of());
        when(contentRepo.findById(ContentId.of(1L))).thenReturn(Optional.of(t1));

        LearnerPathDraft draft = planner().plan(USER, TOPIC_TARGET);

        assertThat(draft.masteryStartSnapshot())
                .containsEntry(TOPIC_PREREQ, MasteryScore.of(0.55));
    }

    @Test
    void everyStepsPrereqsAppearBeforeIt_invariant() {
        // Diamond DAG: target depends on M and N; both depend on root.
        Content target = content(1, TOPIC_TARGET, "T");
        Content m = content(10, TOPIC_PREREQ, "M");
        Content n = content(11, TOPIC_PREREQ, "N");
        Content root = content(100, TOPIC_PREREQ, "Root");
        when(skillRepo.findByUser(USER)).thenReturn(List.of());
        when(contentRepo.findByTopicId(TOPIC_TARGET)).thenReturn(List.of(target));
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(1L)))
                .thenReturn(List.of(edge(1L, 10L), edge(1L, 11L)));
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(10L)))
                .thenReturn(List.of(edge(10L, 100L)));
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(11L)))
                .thenReturn(List.of(edge(11L, 100L)));
        when(prereqRepo.findDirectPrerequisitesOf(ContentId.of(100L))).thenReturn(List.of());
        when(contentRepo.findById(ContentId.of(1L))).thenReturn(Optional.of(target));
        when(contentRepo.findById(ContentId.of(10L))).thenReturn(Optional.of(m));
        when(contentRepo.findById(ContentId.of(11L))).thenReturn(Optional.of(n));
        when(contentRepo.findById(ContentId.of(100L))).thenReturn(Optional.of(root));

        LearnerPathDraft draft = planner().plan(USER, TOPIC_TARGET);

        Map<ContentId, Integer> order = new HashMap<>();
        for (LearnerPathStep s : draft.steps()) {
            order.put(s.contentId(), s.stepOrder());
        }
        // FR-32: every dependent comes after each of its prereqs.
        Set<ContentId> seen = new HashSet<>();
        for (LearnerPathStep s : draft.steps()) {
            for (PrerequisiteEdge e :
                    prereqRepo.findDirectPrerequisitesOf(s.contentId())) {
                if (order.containsKey(e.prereqContentId())) {
                    assertThat(seen)
                            .as(
                                    "prereq %s must precede %s",
                                    e.prereqContentId(), s.contentId())
                            .contains(e.prereqContentId());
                }
            }
            seen.add(s.contentId());
        }
    }
}
