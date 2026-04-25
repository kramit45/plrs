package com.plrs.application.recommendation;

import com.plrs.domain.user.UserId;
import java.util.Optional;

/**
 * Read/write port for the per-user top-N recommendation cache. The
 * Iter 2 {@link com.plrs.application.cache.TopNCache} sibling port is
 * invalidation-only (its sole purpose is to nuke the entry on
 * post-commit signals from the quiz-attempt path); this store is what
 * the recommender use case reads from and writes to.
 *
 * <p>Both ports point at the same Redis key
 * ({@code rec:topN:{userUuid}}), so an invalidate on one is observable
 * by the other.
 *
 * <p>Best-effort: implementations should swallow Redis exceptions on
 * read and return {@link Optional#empty()}, and on write log + drop —
 * the {@code user_skills_version} stamp on the entry remains the
 * authoritative correctness lever (§2.e.2.3.3).
 *
 * <p>Traces to: §2.e.2.3.2 (cache hit/miss flow), §2.e.2.3.3
 * (version-bust).
 */
public interface TopNCacheStore {

    /** Returns the cached entry for {@code userId}, if any. */
    Optional<CachedTopN> get(UserId userId);

    /** Writes (or overwrites) the cached entry for {@code userId}. */
    void put(UserId userId, CachedTopN entry);
}
