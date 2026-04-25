package com.plrs.infrastructure.recommendation;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.recommendation.RecommendationReason;
import com.plrs.domain.recommendation.RecommendationScore;
import com.plrs.domain.user.UserId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Bridge between the {@link Recommendation} aggregate (domain) and
 * {@link RecommendationJpaEntity} (infrastructure). Manual mapper
 * because the aggregate has no public constructor / setters — the
 * only reconstitution path is {@link Recommendation#rehydrate}, so
 * MapStruct's annotation processor cannot generate a valid
 * implementation.
 *
 * <p>Traces to: §3.a (infra maps domain ↔ JPA), §3.c.1.4.
 */
@Component
public class RecommendationMapper {

    public RecommendationJpaEntity toEntity(Recommendation r) {
        if (r == null) {
            return null;
        }
        return new RecommendationJpaEntity(
                r.userId().value(),
                r.contentId().value(),
                r.createdAt(),
                r.score().toBigDecimal(),
                (short) r.rankPosition(),
                r.reason().text(),
                r.modelVariant(),
                r.clickedAt().orElse(null),
                r.completedAt().orElse(null));
    }

    public Recommendation toDomain(RecommendationJpaEntity e) {
        if (e == null) {
            return null;
        }
        return Recommendation.rehydrate(
                UserId.of(e.getUserId()),
                ContentId.of(e.getContentId()),
                e.getCreatedAt(),
                RecommendationScore.of(e.getScore().doubleValue()),
                e.getRankPosition(),
                new RecommendationReason(e.getReasonText()),
                e.getModelVariant(),
                Optional.ofNullable(e.getClickedAt()),
                Optional.ofNullable(e.getCompletedAt()));
    }
}
