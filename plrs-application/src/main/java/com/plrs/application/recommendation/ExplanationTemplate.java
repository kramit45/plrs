package com.plrs.application.recommendation;

import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.recommendation.RecommendationReason;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserId;
import com.plrs.domain.content.ContentId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Builds the human-readable reason string attached to each
 * {@link com.plrs.domain.recommendation.Recommendation}. The template
 * picks from four deterministic phrasings based on which component
 * dominates the {@link Blended} score:
 *
 * <ul>
 *   <li>Cold-start ({@link Blended#coldStart()}): popularity phrasing.
 *   <li>{@code 0.65 * cf > 0.35 * cb}: collaborative-filtering phrasing.
 *   <li>{@code 0.35 * cb > 0.1}: content-similarity phrasing.
 *   <li>Otherwise: generic "highly rated" phrasing.
 * </ul>
 *
 * <p>The thresholds match the {@link HybridRanker} weights so the
 * dominance check sees the same blend that produced the score. All
 * outputs stay within the {@link RecommendationReason#MAX_LENGTH}
 * budget — callers should not re-trim.
 *
 * <p>The {@code userId} parameter is reserved for future personalisation
 * (e.g. surfacing a peer cohort name); it is not consulted today.
 *
 * <p>Traces to: FR-29, §2.e.1.4 P4.6.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class ExplanationTemplate {

    private static final double CB_DOMINANCE_THRESHOLD = 0.10;

    private final ContentRepository contentRepository;
    private final TopicRepository topicRepository;

    public ExplanationTemplate(
            ContentRepository contentRepository, TopicRepository topicRepository) {
        this.contentRepository = contentRepository;
        this.topicRepository = topicRepository;
    }

    public String explain(ContentId candidate, Blended b, UserId userId) {
        String topic = topicName(candidate);
        if (b.coldStart()) {
            return truncate("Popular among learners exploring " + topic);
        }
        double cfContrib = HybridRanker.LAMBDA_BLEND * b.cf();
        double cbContrib = (1.0 - HybridRanker.LAMBDA_BLEND) * b.cb();
        if (cfContrib > cbContrib) {
            return truncate("Recommended because learners similar to you completed it");
        }
        if (cbContrib > CB_DOMINANCE_THRESHOLD) {
            return truncate("Matches your interests in " + topic);
        }
        return truncate("Highly rated content in " + topic);
    }

    private String topicName(ContentId id) {
        return contentRepository
                .findById(id)
                .flatMap(c -> topicRepository.findById(c.topicId()))
                .map(Topic::name)
                .orElse("this topic");
    }

    private static String truncate(String text) {
        if (text == null) {
            return "Recommended for you";
        }
        if (text.length() <= RecommendationReason.MAX_LENGTH) {
            return text;
        }
        return text.substring(0, RecommendationReason.MAX_LENGTH);
    }
}
