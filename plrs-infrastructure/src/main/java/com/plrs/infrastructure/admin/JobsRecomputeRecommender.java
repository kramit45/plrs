package com.plrs.infrastructure.admin;

import com.plrs.application.admin.RecomputeRecommender;
import com.plrs.infrastructure.recommendation.ItemSimilarityJob;
import com.plrs.infrastructure.recommendation.TfIdfBuildJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adapter that wires the two precompute jobs behind the
 * {@link RecomputeRecommender} port. Synchronous — admins click a
 * button and want to know it ran.
 */
@Component
@ConditionalOnProperty(name = {"spring.datasource.url", "spring.data.redis.host"})
public class JobsRecomputeRecommender implements RecomputeRecommender {

    private final ItemSimilarityJob itemSimilarityJob;
    private final TfIdfBuildJob tfIdfBuildJob;

    public JobsRecomputeRecommender(
            ItemSimilarityJob itemSimilarityJob, TfIdfBuildJob tfIdfBuildJob) {
        this.itemSimilarityJob = itemSimilarityJob;
        this.tfIdfBuildJob = tfIdfBuildJob;
    }

    @Override
    public void recomputeNow() {
        itemSimilarityJob.recomputeNow();
        tfIdfBuildJob.rebuildNow();
    }
}
