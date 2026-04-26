package com.plrs.infrastructure.path;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA composite-key class for {@link LearnerPathStepJpaEntity}.
 * Regular class, not record — same Hibernate 6.4.4 {@code @IdClass}
 * NPE-on-record constraint as
 * {@link com.plrs.infrastructure.recommendation.RecommendationKey}.
 */
public class PathStepKey implements Serializable {

    private Long pathId;
    private Integer stepOrder;

    public PathStepKey() {}

    public PathStepKey(Long pathId, Integer stepOrder) {
        this.pathId = pathId;
        this.stepOrder = stepOrder;
    }

    public Long getPathId() {
        return pathId;
    }

    public Integer getStepOrder() {
        return stepOrder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PathStepKey other)) {
            return false;
        }
        return Objects.equals(pathId, other.pathId)
                && Objects.equals(stepOrder, other.stepOrder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pathId, stepOrder);
    }
}
