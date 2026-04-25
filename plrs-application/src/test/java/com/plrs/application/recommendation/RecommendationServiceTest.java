package com.plrs.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
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
class RecommendationServiceTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final UserId USER =
            UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final TopicId TOPIC_A = TopicId.of(1L);
    private static final TopicId TOPIC_B = TopicId.of(2L);

    @Mock private ContentRepository contentRepository;
    @Mock private UserSkillRepository userSkillRepository;
    @Mock private PrerequisiteRepository prerequisiteRepository;
    @Mock private TopicRepository topicRepository;
    @Mock private PopularityScorer popularityScorer;

    private RecommendationService service() {
        return new RecommendationService(
                contentRepository,
                userSkillRepository,
                prerequisiteRepository,
                topicRepository,
                popularityScorer,
                CLOCK);
    }

    private Content content(long id, TopicId topic, Difficulty difficulty, String title) {
        return Content.rehydrate(
                ContentId.of(id),
                topic,
                title,
                ContentType.VIDEO,
                difficulty,
                10,
                "https://x.y/" + id,
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private UserSkill skill(TopicId topic, double mastery) {
        return UserSkill.rehydrate(
                USER, topic, MasteryScore.of(mastery), new BigDecimal("0.500"), T0);
    }

    private Topic topic(long id, String name) {
        return Topic.rehydrate(
                TopicId.of(id),
                name,
                "desc",
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private void stubTopicLookups() {
        lenient().when(topicRepository.findById(TOPIC_A))
                .thenReturn(Optional.of(topic(1L, "Algebra")));
        lenient().when(topicRepository.findById(TOPIC_B))
                .thenReturn(Optional.of(topic(2L, "Calculus")));
    }

    @Test
    void prereqFilterDropsItemsWithUnmetPrerequisites() {
        Content prereqContent = content(10L, TOPIC_A, Difficulty.BEGINNER, "Pre");
        Content gated = content(20L, TOPIC_B, Difficulty.BEGINNER, "Gated");
        Content open = content(30L, TOPIC_A, Difficulty.BEGINNER, "Open");
        when(contentRepository.findAllNonQuiz(200))
                .thenReturn(List.of(prereqContent, gated, open));
        when(userSkillRepository.findByUser(USER))
                .thenReturn(List.of(skill(TOPIC_A, 0.30), skill(TOPIC_B, 0.10)));
        // gated requires prereqContent (topic A); mastery on A is 0.30 < 0.60 → drop.
        when(prerequisiteRepository.findDirectPrerequisitesOf(ContentId.of(10L)))
                .thenReturn(List.of());
        when(prerequisiteRepository.findDirectPrerequisitesOf(ContentId.of(20L)))
                .thenReturn(
                        List.of(
                                new PrerequisiteEdge(
                                        ContentId.of(20L),
                                        ContentId.of(10L),
                                        T0,
                                        Optional.empty())));
        when(prerequisiteRepository.findDirectPrerequisitesOf(ContentId.of(30L)))
                .thenReturn(List.of());
        when(contentRepository.findById(ContentId.of(10L))).thenReturn(Optional.of(prereqContent));
        when(popularityScorer.score(any()))
                .thenReturn(Map.of(ContentId.of(10L), 0.9, ContentId.of(30L), 0.8));
        stubTopicLookups();

        List<Recommendation> recs = service().generate(USER, 5, "popularity_v1");

        assertThat(recs)
                .extracting(r -> r.contentId().value())
                .as("gated item with unmet prereq is filtered out")
                .doesNotContain(20L)
                .containsExactlyInAnyOrder(10L, 30L);
    }

    @Test
    void feasibilityFilterDropsItemsTooHardForCurrentMastery() {
        Content beginner = content(1L, TOPIC_A, Difficulty.BEGINNER, "B");
        Content intermediate = content(2L, TOPIC_A, Difficulty.INTERMEDIATE, "I");
        Content advanced = content(3L, TOPIC_A, Difficulty.ADVANCED, "A");
        when(contentRepository.findAllNonQuiz(200))
                .thenReturn(List.of(beginner, intermediate, advanced));
        // Mastery 0.20 → ceil(0.6) + 1 = 2 → BEGINNER + INTERMEDIATE
        // are feasible, ADVANCED is not.
        when(userSkillRepository.findByUser(USER)).thenReturn(List.of(skill(TOPIC_A, 0.20)));
        when(prerequisiteRepository.findDirectPrerequisitesOf(any())).thenReturn(List.of());
        when(popularityScorer.score(any()))
                .thenAnswer(
                        inv -> {
                            java.util.Collection<ContentId> in = inv.getArgument(0);
                            java.util.Map<ContentId, Double> out = new java.util.HashMap<>();
                            for (ContentId c : in) {
                                out.put(c, 0.5);
                            }
                            return out;
                        });
        stubTopicLookups();

        // k=2 matches the surviving feasible set; backfill from the
        // (full pool - emitted) would add the filtered-out ADVANCED
        // back, which is exactly what FR-30 fallback does. The
        // FR-27-isolation assertion lives in the k==surviving-count
        // case.
        List<Recommendation> recs = service().generate(USER, 2, "popularity_v1");

        assertThat(recs)
                .extracting(r -> r.contentId().value())
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void emptyMasteryReturnsOnlyBeginnerItems() {
        Content beginner = content(1L, TOPIC_A, Difficulty.BEGINNER, "B");
        Content intermediate = content(2L, TOPIC_A, Difficulty.INTERMEDIATE, "I");
        Content advanced = content(3L, TOPIC_A, Difficulty.ADVANCED, "A");
        when(contentRepository.findAllNonQuiz(200))
                .thenReturn(List.of(beginner, intermediate, advanced));
        when(userSkillRepository.findByUser(USER)).thenReturn(List.of());
        when(prerequisiteRepository.findDirectPrerequisitesOf(any())).thenReturn(List.of());
        when(popularityScorer.score(any()))
                .thenAnswer(
                        inv -> {
                            java.util.Collection<ContentId> in = inv.getArgument(0);
                            java.util.Map<ContentId, Double> out = new java.util.HashMap<>();
                            for (ContentId c : in) {
                                out.put(c, 0.5);
                            }
                            return out;
                        });
        stubTopicLookups();

        // k=1 matches the single BEGINNER survivor — keeps the
        // assertion focused on the filter, not the FR-30 backfill.
        List<Recommendation> recs = service().generate(USER, 1, "popularity_v1");

        assertThat(recs)
                .extracting(r -> r.contentId().value())
                .as("empty mastery → only Difficulty.BEGINNER survives feasibility")
                .containsExactly(1L);
    }

    @Test
    void ranksDescendingByScore() {
        Content a = content(1L, TOPIC_A, Difficulty.BEGINNER, "low");
        Content b = content(2L, TOPIC_A, Difficulty.BEGINNER, "mid");
        Content c = content(3L, TOPIC_A, Difficulty.BEGINNER, "hi");
        when(contentRepository.findAllNonQuiz(200)).thenReturn(List.of(a, b, c));
        when(userSkillRepository.findByUser(USER)).thenReturn(List.of());
        when(prerequisiteRepository.findDirectPrerequisitesOf(any())).thenReturn(List.of());
        when(popularityScorer.score(any()))
                .thenReturn(
                        Map.of(
                                ContentId.of(1L), 0.10,
                                ContentId.of(2L), 0.55,
                                ContentId.of(3L), 0.95));
        stubTopicLookups();

        List<Recommendation> recs = service().generate(USER, 5, "popularity_v1");

        assertThat(recs)
                .isSortedAccordingTo(
                        Comparator.comparingInt(Recommendation::rankPosition))
                .extracting(r -> r.contentId().value())
                .containsExactly(3L, 2L, 1L);
        assertThat(recs.get(0).rankPosition()).isEqualTo(1);
        assertThat(recs.get(0).score().value()).isEqualTo(0.95);
    }

    @Test
    void reasonTextStaysWithin200Chars() {
        Content c = content(1L, TOPIC_A, Difficulty.BEGINNER, "x");
        when(contentRepository.findAllNonQuiz(200)).thenReturn(List.of(c));
        when(userSkillRepository.findByUser(USER)).thenReturn(List.of());
        when(prerequisiteRepository.findDirectPrerequisitesOf(any())).thenReturn(List.of());
        when(popularityScorer.score(any())).thenReturn(Map.of(ContentId.of(1L), 0.9));
        // Topic name 250 chars long — service must truncate.
        Topic huge = Topic.rehydrate(
                TOPIC_A,
                "T".repeat(120),
                "d",
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
        when(topicRepository.findById(TOPIC_A)).thenReturn(Optional.of(huge));

        List<Recommendation> recs = service().generate(USER, 1, "popularity_v1");

        assertThat(recs.get(0).reason().text().length()).isLessThanOrEqualTo(200);
    }

    @Test
    void truncatesToK() {
        Content a = content(1L, TOPIC_A, Difficulty.BEGINNER, "1");
        Content b = content(2L, TOPIC_A, Difficulty.BEGINNER, "2");
        Content c = content(3L, TOPIC_A, Difficulty.BEGINNER, "3");
        when(contentRepository.findAllNonQuiz(200)).thenReturn(List.of(a, b, c));
        when(userSkillRepository.findByUser(USER)).thenReturn(List.of());
        when(prerequisiteRepository.findDirectPrerequisitesOf(any())).thenReturn(List.of());
        when(popularityScorer.score(any()))
                .thenReturn(
                        Map.of(
                                ContentId.of(1L), 0.9,
                                ContentId.of(2L), 0.5,
                                ContentId.of(3L), 0.1));
        stubTopicLookups();

        List<Recommendation> recs = service().generate(USER, 2, "popularity_v1");

        assertThat(recs).hasSize(2);
        assertThat(recs).extracting(Recommendation::rankPosition).containsExactly(1, 2);
    }

    @Test
    void backfillsFromUnfilteredPoolWhenFilteredSetIsTooSmall() {
        // 3 candidates: candidates 2 + 3 are gated by an unmet prereq;
        // only candidate 1 survives the prereq filter. With k=3 the
        // service must backfill from the unfiltered pool.
        Content c1 = content(1L, TOPIC_A, Difficulty.BEGINNER, "open");
        Content c2 = content(2L, TOPIC_A, Difficulty.BEGINNER, "gated-1");
        Content c3 = content(3L, TOPIC_A, Difficulty.BEGINNER, "gated-2");
        when(contentRepository.findAllNonQuiz(200)).thenReturn(List.of(c1, c2, c3));
        when(userSkillRepository.findByUser(USER)).thenReturn(List.of());
        Content prereq = content(99L, TOPIC_B, Difficulty.BEGINNER, "must-master");
        when(contentRepository.findById(ContentId.of(99L))).thenReturn(Optional.of(prereq));
        when(prerequisiteRepository.findDirectPrerequisitesOf(ContentId.of(1L)))
                .thenReturn(List.of());
        when(prerequisiteRepository.findDirectPrerequisitesOf(ContentId.of(2L)))
                .thenReturn(
                        List.of(
                                new PrerequisiteEdge(
                                        ContentId.of(2L),
                                        ContentId.of(99L),
                                        T0,
                                        Optional.empty())));
        when(prerequisiteRepository.findDirectPrerequisitesOf(ContentId.of(3L)))
                .thenReturn(
                        List.of(
                                new PrerequisiteEdge(
                                        ContentId.of(3L),
                                        ContentId.of(99L),
                                        T0,
                                        Optional.empty())));
        when(popularityScorer.score(any()))
                .thenAnswer(
                        inv -> {
                            java.util.Collection<ContentId> in = inv.getArgument(0);
                            java.util.Map<ContentId, Double> out = new java.util.HashMap<>();
                            double v = 0.9;
                            for (ContentId c : in) {
                                out.put(c, v);
                                v -= 0.1;
                            }
                            return out;
                        });
        stubTopicLookups();

        List<Recommendation> recs = service().generate(USER, 3, "popularity_v1");

        // 1 from filtered + 2 backfilled (the gated ones, since the
        // backfill pool ignores filters).
        assertThat(recs).hasSize(3);
        assertThat(recs)
                .extracting(r -> r.contentId().value())
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void rejectsZeroOrNegativeK() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service().generate(USER, 0, "popularity_v1"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service().generate(USER, -1, "popularity_v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesModelVariantOntoEveryRecommendation() {
        Content c = content(1L, TOPIC_A, Difficulty.BEGINNER, "x");
        when(contentRepository.findAllNonQuiz(200)).thenReturn(List.of(c));
        when(userSkillRepository.findByUser(USER)).thenReturn(List.of());
        when(prerequisiteRepository.findDirectPrerequisitesOf(any())).thenReturn(List.of());
        when(popularityScorer.score(any())).thenReturn(Map.of(ContentId.of(1L), 0.5));
        stubTopicLookups();

        List<Recommendation> recs = service().generate(USER, 1, "experiment_a");

        assertThat(recs.get(0).modelVariant()).isEqualTo("experiment_a");
    }
}
