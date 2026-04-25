package com.plrs.application.recommendation;

import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.mastery.UserSkill;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.recommendation.RecommendationReason;
import com.plrs.domain.recommendation.RecommendationScore;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Composer that builds the recommender candidate pool, applies the
 * FR-26 prereq filter and the FR-27 feasibility filter, scores via
 * {@link PopularityScorer}, ranks the survivors, truncates to {@code k},
 * and emits {@link Recommendation} aggregates with deterministic
 * reasons. CF / CB hooks land in step 114; for now the popularity
 * score is the only signal.
 *
 * <p>Filtering in order:
 *
 * <ol>
 *   <li>Candidate pool: {@link ContentRepository#findAllNonQuiz} capped
 *       at 200 rows.
 *   <li>Prereq filter (FR-26): drop candidates whose direct prereqs
 *       aren't mastered ≥ {@link #PREREQ_MASTERY_THRESHOLD} on the
 *       prereq's own topic.
 *   <li>Feasibility filter (FR-27): drop items whose difficulty rank
 *       exceeds the learner's mastery + 1 band on that content's
 *       topic. Empty-mastery learners only see BEGINNER content.
 *   <li>Popularity score over the survivors.
 *   <li>Backfill if {@code k} unfilled — pad with raw popularity from
 *       the unfiltered pool (FR-30 fallback).
 * </ol>
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: FR-25, FR-26, FR-27, FR-29, FR-30.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class RecommendationService {

    /** Pool size cap matching the spec. */
    public static final int CANDIDATE_POOL_SIZE = 200;

    /** Mastery threshold above which a prereq is considered "met". */
    public static final double PREREQ_MASTERY_THRESHOLD = 0.60;

    /** Maximum reason text length, bounded by the schema column. */
    public static final int MAX_REASON_LENGTH = RecommendationReason.MAX_LENGTH;

    private final ContentRepository contentRepository;
    private final UserSkillRepository userSkillRepository;
    private final PrerequisiteRepository prerequisiteRepository;
    private final TopicRepository topicRepository;
    private final PopularityScorer popularityScorer;
    private final CfScorer cfScorer;
    private final Clock clock;

    public RecommendationService(
            ContentRepository contentRepository,
            UserSkillRepository userSkillRepository,
            PrerequisiteRepository prerequisiteRepository,
            TopicRepository topicRepository,
            PopularityScorer popularityScorer,
            CfScorer cfScorer,
            Clock clock) {
        this.contentRepository = contentRepository;
        this.userSkillRepository = userSkillRepository;
        this.prerequisiteRepository = prerequisiteRepository;
        this.topicRepository = topicRepository;
        this.popularityScorer = popularityScorer;
        this.cfScorer = cfScorer;
        this.clock = clock;
    }

    public List<Recommendation> generate(UserId userId, int k, String modelVariant) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1, got " + k);
        }

        // 1. Candidate pool (non-QUIZ).
        List<Content> candidates = contentRepository.findAllNonQuiz(CANDIDATE_POOL_SIZE);

        // 2. User mastery snapshot keyed by topic.
        Map<TopicId, MasteryScore> mastery = new HashMap<>();
        for (UserSkill s : userSkillRepository.findByUser(userId)) {
            mastery.put(s.topicId(), s.mastery());
        }

        // Index candidates by id for the filter loops.
        Map<ContentId, Content> byId = new HashMap<>();
        for (Content c : candidates) {
            byId.put(c.id(), c);
        }

        // 3. Prereq filter (FR-26).
        Set<ContentId> prereqOk = new LinkedHashSet<>();
        for (Content c : candidates) {
            if (allPrereqsMet(c, mastery)) {
                prereqOk.add(c.id());
            }
        }

        // 4. Feasibility filter (FR-27).
        Set<ContentId> feasible = new LinkedHashSet<>();
        for (ContentId cid : prereqOk) {
            Content c = byId.get(cid);
            if (c != null && isFeasible(c, mastery)) {
                feasible.add(cid);
            }
        }

        // 5. Hybrid score = 0.5 * popularity + 0.5 * CF (interim
        //    blend; step 119 introduces the proper λ-weighted hybrid
        //    with cold-start detection).
        Map<ContentId, Double> popScores = popularityScorer.score(feasible);
        Map<ContentId, Double> cfScores = cfScorer.score(userId, feasible);
        Map<ContentId, Double> scores = new HashMap<>(feasible.size());
        for (ContentId cid : feasible) {
            double pop = popScores.getOrDefault(cid, 0.0);
            double cf = cfScores.getOrDefault(cid, 0.0);
            scores.put(cid, 0.5 * pop + 0.5 * cf);
        }
        List<ContentId> ordered =
                scores.entrySet().stream()
                        .sorted(Map.Entry.<ContentId, Double>comparingByValue().reversed())
                        .map(Map.Entry::getKey)
                        .toList();

        List<Recommendation> out = new ArrayList<>(k);
        Instant now = Instant.now(clock);
        int rank = 1;
        for (ContentId cid : ordered) {
            if (rank > k) {
                break;
            }
            Content c = byId.get(cid);
            if (c == null) {
                continue;
            }
            out.add(buildRecommendation(userId, c, scores.get(cid), rank++, modelVariant, now));
        }

        // 6. Backfill (FR-30): if we under-filled, pad with the
        //    unfiltered candidate pool's popularity, skipping any id
        //    we already emitted.
        if (out.size() < k) {
            Set<ContentId> alreadyEmitted = new java.util.HashSet<>();
            for (Recommendation r : out) {
                alreadyEmitted.add(r.contentId());
            }
            Set<ContentId> wholePool = new LinkedHashSet<>(byId.keySet());
            wholePool.removeAll(alreadyEmitted);
            Map<ContentId, Double> fallbackScores = popularityScorer.score(wholePool);
            // Only consider ids the scorer was asked about; defensive
            // against scorers that return more keys than they were
            // queried for (e.g. mocks).
            List<ContentId> fallbackOrder =
                    fallbackScores.entrySet().stream()
                            .filter(e -> wholePool.contains(e.getKey()))
                            .sorted(
                                    Map.Entry.<ContentId, Double>comparingByValue()
                                            .reversed())
                            .map(Map.Entry::getKey)
                            .toList();
            for (ContentId cid : fallbackOrder) {
                if (out.size() >= k) {
                    break;
                }
                Content c = byId.get(cid);
                if (c == null) {
                    continue;
                }
                out.add(
                        buildRecommendation(
                                userId, c, fallbackScores.get(cid), rank++, modelVariant, now));
            }
        }

        return out;
    }

    private boolean allPrereqsMet(Content c, Map<TopicId, MasteryScore> mastery) {
        List<PrerequisiteEdge> prereqs =
                prerequisiteRepository.findDirectPrerequisitesOf(c.id());
        for (PrerequisiteEdge p : prereqs) {
            Content pc = contentRepository.findById(p.prereqContentId()).orElse(null);
            if (pc == null) {
                // Dangling prereq pointer (rare; cleanup happens via
                // ON DELETE CASCADE). Treat as met so the candidate
                // doesn't get dropped on stale state.
                continue;
            }
            MasteryScore m = mastery.getOrDefault(pc.topicId(), MasteryScore.ZERO);
            if (m.value() < PREREQ_MASTERY_THRESHOLD) {
                return false;
            }
        }
        return true;
    }

    /**
     * Feasibility (FR-27): item is feasible iff its difficulty rank is
     * ≤ ceil(topicMastery × 3) + 1, capped at 3 (ADVANCED). Empty
     * mastery → 0 → only BEGINNER (rank 1) survives.
     */
    private boolean isFeasible(Content c, Map<TopicId, MasteryScore> mastery) {
        double topicMastery = mastery.getOrDefault(c.topicId(), MasteryScore.ZERO).value();
        int allowedRank = Math.min((int) Math.ceil(topicMastery * 3) + 1, 3);
        return c.difficulty().rank() <= allowedRank;
    }

    private Recommendation buildRecommendation(
            UserId userId,
            Content c,
            double rawScore,
            int rank,
            String modelVariant,
            Instant now) {
        String topicName =
                topicRepository.findById(c.topicId()).map(Topic::name).orElse("(unknown)");
        String reason =
                truncateReason(
                        "Popular and similar to what you've liked in " + topicName);
        return Recommendation.create(
                userId,
                c.id(),
                RecommendationScore.of(rawScore),
                rank,
                new RecommendationReason(reason),
                modelVariant,
                Clock.fixed(now, java.time.ZoneOffset.UTC));
    }

    private static String truncateReason(String text) {
        if (text == null) {
            return "Recommended for you";
        }
        if (text.length() <= MAX_REASON_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_REASON_LENGTH);
    }
}
