package com.plrs.application.admin;

/**
 * Application port to fire the offline recommender precompute jobs
 * synchronously. Used by the admin recompute endpoint so the Newman
 * Iter 3 flow doesn't have to sleep waiting for the cron timers.
 */
public interface RecomputeRecommender {

    /** Synchronously runs both the item-item CF and TF-IDF builds. */
    void recomputeNow();
}
