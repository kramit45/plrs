package com.plrs.web.recommendation;

import com.plrs.application.recommendation.GenerateRecommendationsUseCase;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserId;
import com.plrs.web.common.PerUserRateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session-authenticated mirror of {@link RecommendationController} for
 * the dashboard's same-origin browser fetch (the dual-endpoint pattern
 * from step 72: {@code /api/**} carries JWTs, {@code /web-api/**}
 * rides the form-login chain's session cookie).
 *
 * <p>Always serves the caller themselves (no {@code userId} override —
 * if you want to peek at another user's recommendations, use the JWT
 * surface and your ADMIN role). STUDENT-only via {@code @PreAuthorize}.
 *
 * <p>Same {@link PerUserRateLimiter} as the JWT controller so the
 * 20 req/min/user budget is enforced regardless of which surface the
 * client uses.
 *
 * <p>Traces to: FR-24, FR-35.
 */
@RestController
@RequestMapping("/web-api/recommendations")
@ConditionalOnProperty(name = "spring.datasource.url")
public class WebRecommendationController {

    static final int MIN_K = 1;
    static final int MAX_K = 50;

    private final GenerateRecommendationsUseCase useCase;
    private final ContentRepository contentRepository;
    private final TopicRepository topicRepository;
    private final PerUserRateLimiter rateLimiter;

    public WebRecommendationController(
            GenerateRecommendationsUseCase useCase,
            ContentRepository contentRepository,
            TopicRepository topicRepository,
            PerUserRateLimiter rateLimiter) {
        this.useCase = useCase;
        this.contentRepository = contentRepository;
        this.topicRepository = topicRepository;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    @PreAuthorize("hasRole('STUDENT')")
    public List<RecommendationResponse> get(@RequestParam(defaultValue = "10") int k) {
        if (k < MIN_K || k > MAX_K) {
            throw new IllegalArgumentException(
                    "k must be in [" + MIN_K + ", " + MAX_K + "], got " + k);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID principalId = UUID.fromString(auth.getName());
        rateLimiter.tryAcquire(principalId);

        List<Recommendation> recs = useCase.handle(UserId.of(principalId), k);
        List<RecommendationResponse> out = new ArrayList<>(recs.size());
        for (Recommendation r : recs) {
            Content content = contentRepository.findById(r.contentId()).orElse(null);
            if (content == null) {
                continue;
            }
            String topicName =
                    topicRepository
                            .findById(content.topicId())
                            .map(Topic::name)
                            .orElse("(unknown)");
            out.add(
                    new RecommendationResponse(
                            content.id().value(),
                            content.title(),
                            topicName,
                            content.ctype().name(),
                            content.difficulty().name(),
                            content.estMinutes(),
                            r.score().value(),
                            r.rankPosition(),
                            r.reason().text()));
        }
        return out;
    }
}
