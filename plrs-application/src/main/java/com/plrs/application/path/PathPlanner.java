package com.plrs.application.path;

import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.mastery.UserSkill;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.path.LearnerPathDraft;
import com.plrs.domain.path.LearnerPathStep;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Algorithm A6 (§3.c.5.6): build a prerequisite-aware learner path
 * from the learner's current mastery to a target topic.
 *
 * <p>Pipeline:
 *
 * <ol>
 *   <li>Snapshot the learner's mastery (per topic) so the result is
 *       reproducible from the persisted snapshot at audit time.
 *   <li>Pull the candidate set from {@code findByTopicId(target)} —
 *       these are the items whose mastery the learner cares about.
 *   <li>BFS backwards over the prereq DAG to collect every transitively
 *       required item.
 *   <li>Prune items whose owning topic mastery is already
 *       {@code >= SKIP_THRESHOLD} (FR-32 — don't ask the learner to
 *       redo content they've already mastered).
 *   <li>Topologically sort (Kahn) the pruned set so every step's
 *       prerequisites appear earlier in the result.
 *   <li>Build {@link LearnerPathStep}s with deterministic reasons,
 *       tagging target-topic items distinctly from prereq items.
 * </ol>
 *
 * <p>Throws on unrecoverable inputs:
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} — empty target topic;
 *   <li>{@link IllegalStateException} — DAG cycle detected.
 *       (V7 + step 56's SERIALIZABLE-isolation cycle check are the
 *       primary guards; this throw is the planner's defensive
 *       follow-up so we never silently emit a partial sort.)
 * </ul>
 *
 * <p>Review-step insertion (FR-32 decay handling) is deferred to
 * Iter 5 per the step prompt — the {@code addedAsReview} flag lives
 * on {@link LearnerPathStep} so a future planner pass can populate it
 * without a schema change.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: §3.c.5.6 (algorithm A6), FR-31 (path generation),
 * FR-32 (skip mastered, prereq compliance).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class PathPlanner {

    /** Topic mastery at or above this fraction → drop the item. */
    public static final double SKIP_THRESHOLD = 0.75;

    private final ContentRepository contentRepository;
    private final PrerequisiteRepository prerequisiteRepository;
    private final UserSkillRepository userSkillRepository;
    private final Clock clock;
    /** Optional FR-40 tunable; falls back to {@link #SKIP_THRESHOLD} when absent. */
    private final org.springframework.beans.factory.ObjectProvider<
                    com.plrs.application.admin.ConfigParamService>
            configProvider;

    public PathPlanner(
            ContentRepository contentRepository,
            PrerequisiteRepository prerequisiteRepository,
            UserSkillRepository userSkillRepository,
            Clock clock,
            org.springframework.beans.factory.ObjectProvider<
                            com.plrs.application.admin.ConfigParamService>
                    configProvider) {
        this.contentRepository = contentRepository;
        this.prerequisiteRepository = prerequisiteRepository;
        this.userSkillRepository = userSkillRepository;
        this.clock = clock;
        this.configProvider = configProvider;
    }

    private double skipThreshold() {
        com.plrs.application.admin.ConfigParamService svc =
                configProvider == null ? null : configProvider.getIfAvailable();
        if (svc == null) {
            return SKIP_THRESHOLD;
        }
        java.util.OptionalDouble cfg = svc.getDouble("path.skip_threshold");
        return cfg.isPresent() ? cfg.getAsDouble() : SKIP_THRESHOLD;
    }

    public LearnerPathDraft plan(UserId userId, TopicId targetTopicId) {
        // 1. Snapshot mastery.
        Map<TopicId, MasteryScore> mastery = new HashMap<>();
        for (UserSkill s : userSkillRepository.findByUser(userId)) {
            mastery.put(s.topicId(), s.mastery());
        }

        // 2. Targets.
        List<Content> targets = contentRepository.findByTopicId(targetTopicId);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "Target topic " + targetTopicId.value() + " has no content");
        }

        // 3. BFS backwards over the prereq DAG.
        Set<ContentId> required = new HashSet<>();
        Deque<ContentId> queue = new ArrayDeque<>();
        for (Content t : targets) {
            queue.push(t.id());
        }
        Map<ContentId, List<PrerequisiteEdge>> prereqCache = new HashMap<>();
        while (!queue.isEmpty()) {
            ContentId c = queue.pop();
            if (!required.add(c)) {
                continue;
            }
            List<PrerequisiteEdge> edges = prereqCache.computeIfAbsent(
                    c, prerequisiteRepository::findDirectPrerequisitesOf);
            for (PrerequisiteEdge e : edges) {
                queue.push(e.prereqContentId());
            }
        }

        // 4. Prune: drop items whose owning topic is already mastered.
        Map<ContentId, Content> contentCache = new HashMap<>();
        Set<ContentId> pruned = new HashSet<>();
        double skipThreshold = skipThreshold();
        for (ContentId cid : required) {
            Content c = contentRepository.findById(cid).orElse(null);
            if (c == null) {
                continue;
            }
            contentCache.put(cid, c);
            double topicMastery =
                    mastery.getOrDefault(c.topicId(), MasteryScore.ZERO).value();
            if (topicMastery < skipThreshold) {
                pruned.add(cid);
            }
        }

        // 5. Kahn topological sort. We use the BFS-cached prereqs;
        //    for any remaining items we may not have looked at (only
        //    happens when the prune dropped a target's prereq), look
        //    up on demand.
        Map<ContentId, Integer> inDegree = new HashMap<>();
        Map<ContentId, List<ContentId>> children = new HashMap<>();
        for (ContentId c : pruned) {
            inDegree.put(c, 0);
            children.put(c, new ArrayList<>());
        }
        for (ContentId cid : pruned) {
            List<PrerequisiteEdge> edges =
                    prereqCache.computeIfAbsent(
                            cid, prerequisiteRepository::findDirectPrerequisitesOf);
            for (PrerequisiteEdge e : edges) {
                ContentId pre = e.prereqContentId();
                if (pruned.contains(pre)) {
                    inDegree.merge(cid, 1, Integer::sum);
                    children.get(pre).add(cid);
                }
            }
        }
        Deque<ContentId> roots = new ArrayDeque<>();
        for (Map.Entry<ContentId, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                roots.add(e.getKey());
            }
        }
        List<ContentId> ordered = new ArrayList<>();
        while (!roots.isEmpty()) {
            ContentId r = roots.poll();
            ordered.add(r);
            for (ContentId child : children.get(r)) {
                int d = inDegree.merge(child, -1, Integer::sum);
                if (d == 0) {
                    roots.add(child);
                }
            }
        }
        if (ordered.size() != pruned.size()) {
            throw new IllegalStateException(
                    "Prereq DAG cycle detected while planning path for user "
                            + userId.value()
                            + " → topic "
                            + targetTopicId.value());
        }

        // 6. Build steps with reasons.
        Set<ContentId> targetIds = new HashSet<>();
        for (Content t : targets) {
            targetIds.add(t.id());
        }
        List<LearnerPathStep> steps = new ArrayList<>(ordered.size());
        int order = 1;
        for (ContentId cid : ordered) {
            String reason =
                    targetIds.contains(cid)
                            ? "Target-topic item"
                            : "Prerequisite for target topic";
            steps.add(LearnerPathStep.pending(order++, cid, false, truncate(reason)));
        }

        return new LearnerPathDraft(userId, targetTopicId, steps, mastery);
    }

    private static String truncate(String s) {
        return s.length() <= LearnerPathStep.MAX_REASON_LENGTH
                ? s
                : s.substring(0, LearnerPathStep.MAX_REASON_LENGTH);
    }

    /** Exposed so tests can hold the same Clock the production wiring uses. */
    Clock clock() {
        return clock;
    }
}
