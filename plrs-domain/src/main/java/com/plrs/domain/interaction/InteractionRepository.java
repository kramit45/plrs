package com.plrs.domain.interaction;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.time.Instant;

/**
 * Domain port for the implicit-feedback event stream. The adapter (step
 * 71, infrastructure module) writes to {@code plrs_ops.interactions}
 * (V8). Composite-PK violations from concurrent writers surface as
 * {@link org.springframework.dao.DataIntegrityViolationException}; the
 * use-case layer wraps them.
 *
 * <p>{@link #existsViewSince(UserId, ContentId, Instant)} backs the
 * FR-15 10-minute VIEW debounce: the use case asks "is there a VIEW for
 * this {@code (user, content)} pair newer than now − 10min?" before
 * issuing the INSERT. Returning a boolean (rather than fetching the
 * row) keeps the read minimal and lets the adapter use a {@code COUNT}
 * on the {@code idx_interactions_user_recent} index.
 *
 * <p>Traces to: §3.c.1.4 (interactions schema), FR-15 (VIEW debounce),
 * FR-16 (interaction events), FR-17 (rating).
 */
public interface InteractionRepository {

    /**
     * Persists the event. Composite-PK violations bubble as
     * {@link org.springframework.dao.DataIntegrityViolationException}.
     */
    void save(InteractionEvent event);

    /**
     * Returns {@code true} iff a VIEW event for {@code (userId, contentId)}
     * has {@code occurredAt > since}. Used by the FR-15 debounce check.
     */
    boolean existsViewSince(UserId userId, ContentId contentId, Instant since);
}
