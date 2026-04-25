package com.plrs.application.recommendation;

import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.recommendation.RecommendationRepository;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached front door for the recommender. On a hit the cache returns
 * the persisted slate without recomputing; on a miss (or a stale
 * version) the use case asks {@link RecommendationService} for a
 * fresh slate, persists it via {@link RecommendationRepository}, and
 * writes the cache entry stamped with the user's current
 * {@code user_skills_version} so a subsequent read can validate it.
 *
 * <p>Cache freshness is governed by the version stamp, not the TTL
 * (§2.e.2.3.3). A larger {@code k} than the cached slate forces a
 * recompute, since trimming from a larger cached list to a smaller k
 * is fine but going the other way isn't.
 *
 * <p>Model variant is {@code cf_v1} when the user has any positive
 * interactions ({@link InteractionRepository#countPositivesForUser}
 * {@code > 0}) and {@code popularity_v1} otherwise — the cold-start
 * branch the offline harness will track.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: §2.e.2.3.2 (cache hit/miss), §2.e.2.3.3 (version-bust),
 * FR-25.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
@Transactional
public class GenerateRecommendationsUseCase {

    /** Variant tag used when the user has at least one positive event. */
    public static final String VARIANT_CF = "cf_v1";

    /** Cold-start variant tag — popularity-only blend ineffective for new users. */
    public static final String VARIANT_POPULARITY = Recommendation.DEFAULT_MODEL_VARIANT;

    private final RecommendationService recommendationService;
    private final RecommendationRepository recommendationRepository;
    private final UserRepository userRepository;
    private final InteractionRepository interactionRepository;
    private final TopNCacheStore cacheStore;
    private final Clock clock;

    public GenerateRecommendationsUseCase(
            RecommendationService recommendationService,
            RecommendationRepository recommendationRepository,
            UserRepository userRepository,
            InteractionRepository interactionRepository,
            TopNCacheStore cacheStore,
            Clock clock) {
        this.recommendationService = recommendationService;
        this.recommendationRepository = recommendationRepository;
        this.userRepository = userRepository;
        this.interactionRepository = interactionRepository;
        this.cacheStore = cacheStore;
        this.clock = clock;
    }

    public List<Recommendation> handle(UserId userId, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1, got " + k);
        }

        long currentVersion = userRepository.getSkillsVersion(userId);
        Optional<CachedTopN> cached = cacheStore.get(userId);
        if (cached.isPresent()
                && cached.get().version() == currentVersion
                && cached.get().items().size() >= k) {
            return cached.get().items().subList(0, k);
        }

        String variant =
                interactionRepository.countPositivesForUser(userId) > 0L
                        ? VARIANT_CF
                        : VARIANT_POPULARITY;
        List<Recommendation> recs =
                recommendationService.generate(userId, k, variant);
        recommendationRepository.saveAll(recs);
        cacheStore.put(userId, new CachedTopN(currentVersion, recs, Instant.now(clock)));
        return recs;
    }
}
