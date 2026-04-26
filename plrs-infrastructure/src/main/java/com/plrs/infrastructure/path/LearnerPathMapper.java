package com.plrs.infrastructure.path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathStatus;
import com.plrs.domain.path.LearnerPathStep;
import com.plrs.domain.path.PathId;
import com.plrs.domain.path.StepStatus;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Bridge between the {@link LearnerPath} aggregate and the JPA
 * entities. JSONB snapshots round-trip via Jackson — keys stringified
 * to keep the JSON shape simple ({@code {"42": 0.7, "10": 0.5}}).
 *
 * <p>Traces to: §3.c.1.4, FR-31.
 */
@Component
public class LearnerPathMapper {

    private final ObjectMapper objectMapper;

    public LearnerPathMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LearnerPathJpaEntity toEntity(LearnerPath path) {
        LearnerPathJpaEntity e = new LearnerPathJpaEntity();
        e.setPathId(path.id().map(PathId::value).orElse(null));
        e.setUserId(path.userId().value());
        e.setTargetTopicId(path.targetTopicId().value());
        e.setStatus(path.status().name());
        e.setGeneratedAt(path.generatedAt());
        e.setStartedAt(path.startedAt().orElse(null));
        e.setPausedAt(path.pausedAt().orElse(null));
        e.setCompletedAt(path.completedAt().orElse(null));
        e.setAbandonedAt(path.abandonedAt().orElse(null));
        e.setSupersededAt(path.supersededAt().orElse(null));
        e.setSupersededBy(path.supersededBy().map(PathId::value).orElse(null));
        e.setMasteryStartSnapshot(serialiseSnapshot(path.masteryStartSnapshot()));
        e.setMasteryEndSnapshot(
                path.masteryEndSnapshot().map(this::serialiseSnapshot).orElse(null));
        return e;
    }

    public LearnerPathStepJpaEntity toStepEntity(Long pathId, LearnerPathStep step) {
        LearnerPathStepJpaEntity e = new LearnerPathStepJpaEntity();
        e.setPathId(pathId);
        e.setStepOrder(step.stepOrder());
        e.setContentId(step.contentId().value());
        e.setStepStatus(step.status().name());
        e.setAddedAsReview(step.addedAsReview());
        e.setReasonInPath(step.reasonInPath());
        e.setStartedAt(step.startedAt().orElse(null));
        e.setCompletedAt(step.completedAt().orElse(null));
        return e;
    }

    public LearnerPath toDomain(
            LearnerPathJpaEntity e, List<LearnerPathStepJpaEntity> stepEntities) {
        List<LearnerPathStep> steps = new ArrayList<>(stepEntities.size());
        for (LearnerPathStepJpaEntity se : stepEntities) {
            steps.add(
                    new LearnerPathStep(
                            se.getStepOrder(),
                            ContentId.of(se.getContentId()),
                            StepStatus.valueOf(se.getStepStatus()),
                            se.isAddedAsReview(),
                            se.getReasonInPath(),
                            Optional.ofNullable(se.getStartedAt()),
                            Optional.ofNullable(se.getCompletedAt())));
        }
        Optional<Map<TopicId, MasteryScore>> endSnap =
                Optional.ofNullable(e.getMasteryEndSnapshot()).map(this::deserialiseSnapshot);
        return LearnerPath.rehydrate(
                PathId.of(e.getPathId()),
                UserId.of(e.getUserId()),
                TopicId.of(e.getTargetTopicId()),
                LearnerPathStatus.valueOf(e.getStatus()),
                e.getGeneratedAt(),
                Optional.ofNullable(e.getStartedAt()),
                Optional.ofNullable(e.getPausedAt()),
                Optional.ofNullable(e.getCompletedAt()),
                Optional.ofNullable(e.getAbandonedAt()),
                Optional.ofNullable(e.getSupersededAt()),
                Optional.ofNullable(e.getSupersededBy()).map(PathId::of),
                steps,
                deserialiseSnapshot(e.getMasteryStartSnapshot()),
                endSnap);
    }

    String serialiseSnapshot(Map<TopicId, MasteryScore> snapshot) {
        Map<String, BigDecimal> raw = new LinkedHashMap<>();
        for (Map.Entry<TopicId, MasteryScore> entry : snapshot.entrySet()) {
            raw.put(String.valueOf(entry.getKey().value()), BigDecimal.valueOf(entry.getValue().value()));
        }
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialise mastery snapshot", ex);
        }
    }

    Map<TopicId, MasteryScore> deserialiseSnapshot(String json) {
        try {
            Map<String, BigDecimal> raw =
                    objectMapper.readValue(
                            json, new TypeReference<Map<String, BigDecimal>>() {});
            Map<TopicId, MasteryScore> out = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> e : raw.entrySet()) {
                out.put(TopicId.of(Long.valueOf(e.getKey())), MasteryScore.of(e.getValue().doubleValue()));
            }
            return out;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialise mastery snapshot", ex);
        }
    }
}
