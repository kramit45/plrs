package com.plrs.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.recommendation.RecommendationReason;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExplanationTemplateTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);
    private static final UserId USER =
            UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final TopicId TOPIC = TopicId.of(7L);
    private static final ContentId CONTENT = ContentId.of(101L);

    @Mock private ContentRepository contentRepository;
    @Mock private TopicRepository topicRepository;

    private ExplanationTemplate template() {
        return new ExplanationTemplate(contentRepository, topicRepository);
    }

    private Content content(String title) {
        return Content.rehydrate(
                CONTENT,
                TOPIC,
                title,
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                "https://x.y/101",
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private Topic topic(String name) {
        return Topic.rehydrate(
                TOPIC,
                name,
                "desc",
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    @BeforeEach
    void stubTopicLookups() {
        lenient()
                .when(contentRepository.findById(CONTENT))
                .thenReturn(Optional.of(content("Algebra basics")));
        lenient()
                .when(topicRepository.findById(TOPIC))
                .thenReturn(Optional.of(topic("Algebra")));
    }

    @Test
    void coldStartReturnsPopularityReason() {
        Blended b = new Blended(0.5, 0.0, 0.0, 0.5, true);
        String text = template().explain(CONTENT, b, USER);
        assertThat(text).isEqualTo("Popular among learners exploring Algebra");
    }

    @Test
    void cfDominantReturnsSimilarLearnersReason() {
        // 0.65 * 0.8 = 0.52, 0.35 * 0.5 = 0.175 → CF wins.
        Blended b = new Blended(0.65, 0.8, 0.5, 0.1, false);
        String text = template().explain(CONTENT, b, USER);
        assertThat(text)
                .isEqualTo("Recommended because learners similar to you completed it");
    }

    @Test
    void cbDominantAboveThresholdReturnsInterestsReason() {
        // 0.65 * 0.1 = 0.065, 0.35 * 0.9 = 0.315 → CB wins, 0.315 > 0.10.
        Blended b = new Blended(0.4, 0.1, 0.9, 0.1, false);
        String text = template().explain(CONTENT, b, USER);
        assertThat(text).isEqualTo("Matches your interests in Algebra");
    }

    @Test
    void cbBelowThresholdFallsBackToHighlyRated() {
        // 0.65 * 0.1 = 0.065, 0.35 * 0.2 = 0.07 → CB wins by tie-break,
        // but 0.07 ≤ 0.10 → "highly rated" branch.
        Blended b = new Blended(0.1, 0.1, 0.2, 0.1, false);
        String text = template().explain(CONTENT, b, USER);
        assertThat(text).isEqualTo("Highly rated content in Algebra");
    }

    @Test
    void unknownContentFallsBackToGenericTopicLabel() {
        when(contentRepository.findById(CONTENT)).thenReturn(Optional.empty());
        Blended b = new Blended(0.5, 0.0, 0.0, 0.5, true);
        String text = template().explain(CONTENT, b, USER);
        assertThat(text).isEqualTo("Popular among learners exploring this topic");
    }

    @Test
    void allReasonsRespect200CharBudget() {
        // Topic.name() is itself capped at 120 chars by a domain
        // invariant, so the longest possible reason is the prefix +
        // 120-char topic — well under the 200-char schema column. The
        // truncate guard inside ExplanationTemplate is belt-and-braces;
        // this test pins the property by exercising every branch with
        // the maximum-length topic name.
        when(topicRepository.findById(TOPIC))
                .thenReturn(Optional.of(topic("T".repeat(120))));

        ExplanationTemplate t = template();
        Blended cold = new Blended(0.5, 0.0, 0.0, 0.5, true);
        Blended cf = new Blended(0.65, 0.8, 0.5, 0.1, false);
        Blended cb = new Blended(0.4, 0.1, 0.9, 0.1, false);
        Blended hi = new Blended(0.1, 0.1, 0.2, 0.1, false);

        assertThat(t.explain(CONTENT, cold, USER).length())
                .isLessThanOrEqualTo(RecommendationReason.MAX_LENGTH);
        assertThat(t.explain(CONTENT, cf, USER).length())
                .isLessThanOrEqualTo(RecommendationReason.MAX_LENGTH);
        assertThat(t.explain(CONTENT, cb, USER).length())
                .isLessThanOrEqualTo(RecommendationReason.MAX_LENGTH);
        assertThat(t.explain(CONTENT, hi, USER).length())
                .isLessThanOrEqualTo(RecommendationReason.MAX_LENGTH);
    }
}
