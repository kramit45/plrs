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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON recommender API. {@code GET /api/recommendations?k=10}.
 *
 * <p>Authorization:
 *
 * <ul>
 *   <li>{@code @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")}
 *       guards the method itself.
 *   <li>An explicit {@code userId} parameter is allowed only for the
 *       caller themselves or for an ADMIN — non-ADMINs trying to
 *       request another user's recs receive 403.
 * </ul>
 *
 * <p>Validation: {@code k} must be in {@code [1, 50]}. Per-user rate
 * limit is enforced via {@link PerUserRateLimiter} (NFR-31, 20
 * req/min/user); breaches throw {@code RateLimitedException} which the
 * global handler maps to 429 with {@code Retry-After}.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: FR-24 (recommend endpoint), NFR-31 (rate limit).
 */
@RestController
@RequestMapping("/api/recommendations")
@ConditionalOnProperty(name = "spring.datasource.url")
public class RecommendationController {

    static final int MIN_K = 1;
    static final int MAX_K = 50;
    static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final GenerateRecommendationsUseCase useCase;
    private final ContentRepository contentRepository;
    private final TopicRepository topicRepository;
    private final PerUserRateLimiter rateLimiter;

    public RecommendationController(
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
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public List<RecommendationResponse> get(
            @RequestParam(defaultValue = "10") int k,
            @RequestParam(required = false) UUID userId) {
        if (k < MIN_K || k > MAX_K) {
            throw new IllegalArgumentException(
                    "k must be in [" + MIN_K + ", " + MAX_K + "], got " + k);
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID principalId = UUID.fromString(auth.getName());
        UUID effectiveId = userId != null ? userId : principalId;

        if (!effectiveId.equals(principalId) && !isAdmin(auth)) {
            throw new AccessDeniedException(
                    "only ADMIN may request another user's recommendations");
        }

        // Rate limit on the effective user (so an ADMIN making bulk
        // calls on behalf of one student doesn't ping every student's
        // own bucket).
        rateLimiter.tryAcquire(effectiveId);

        List<Recommendation> recs = useCase.handle(UserId.of(effectiveId), k);
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

    private static boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()));
    }
}
