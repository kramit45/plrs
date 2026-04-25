package com.plrs.application.cache;

import com.plrs.domain.user.UserId;

/**
 * Application-layer port for the per-user "top-N recommendations" cache.
 * The recommender (Iter 3) reads this cache for hot lookups; for now the
 * port exists only so {@code SubmitQuizAttemptUseCase} can register a
 * post-commit invalidation hook (TX-04) — Iter 2 has no recommender yet,
 * but mastery changed, so any cached top-N for that user is now stale.
 *
 * <p>The {@link #invalidate} contract is best-effort. The
 * {@code user_skills_version} bump performed in the same transaction
 * (TX-01, step 90) is the authoritative invariant: a stale cache read
 * combined with a fresh version bump still produces a correct
 * recommender response, because consumers compare the cached version
 * against the current one and re-compute on mismatch (§2.e.2.3.3).
 *
 * <p>Traces to: §2.e.2.3.3 (cache version invariant), TX-04
 * (post-commit cache invalidation).
 */
public interface TopNCache {

    /** Invalidates the cached top-N entry for {@code userId}. Best-effort. */
    void invalidate(UserId userId);
}
