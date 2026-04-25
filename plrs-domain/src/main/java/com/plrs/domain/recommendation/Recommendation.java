package com.plrs.domain.recommendation;

import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root for one row of {@code plrs_ops.recommendations}
 * (§3.c.1.4): a single (user, content, created_at) recommendation
 * served by the recommender, plus optional click / completion
 * timestamps that downstream CTR analytics fill in later.
 *
 * <p>Immutable — {@link #recordClick(Instant)} returns a fresh
 * instance rather than mutating, so the aggregate stays safe to
 * publish through events.
 *
 * <p>Invariants enforced by the canonical constructor:
 *
 * <ul>
 *   <li>Identity fields ({@code userId}, {@code contentId},
 *       {@code createdAt}) and value fields ({@code score},
 *       {@code rankPosition}, {@code reason}, {@code modelVariant})
 *       are non-null.
 *   <li>{@code rankPosition} is in {@code [1, 50]} —
 *       {@code recs_rank_bounded} CHECK on the schema.
 *   <li>{@code modelVariant} is trimmed-non-blank and at most 30
 *       characters, matching {@code VARCHAR(30) NOT NULL DEFAULT
 *       'popularity_v1'}.
 *   <li>If {@code completedAt} is present, {@code clickedAt} must be
 *       present and {@code clickedAt <= completedAt} — a click must
 *       precede a completion.
 *   <li>{@code clickedAt}, when present, must be {@code >= createdAt}.
 * </ul>
 *
 * <p>Equality is based on the natural composite key
 * {@code (userId, contentId, createdAt)}.
 *
 * <p>Traces to: §3.c.1.4 (recommendations schema), FR-29 (reason text).
 */
public final class Recommendation {

    /** The default {@code model_variant} written by the popularity baseline. */
    public static final String DEFAULT_MODEL_VARIANT = "popularity_v1";

    /** Inclusive upper bound on rank_position from the schema CHECK. */
    public static final int MAX_RANK = 50;

    /** Maximum length of the {@code model_variant} VARCHAR. */
    public static final int MAX_MODEL_VARIANT_LENGTH = 30;

    private final UserId userId;
    private final ContentId contentId;
    private final Instant createdAt;
    private final RecommendationScore score;
    private final int rankPosition;
    private final RecommendationReason reason;
    private final String modelVariant;
    private final Optional<Instant> clickedAt;
    private final Optional<Instant> completedAt;

    private Recommendation(
            UserId userId,
            ContentId contentId,
            Instant createdAt,
            RecommendationScore score,
            int rankPosition,
            RecommendationReason reason,
            String modelVariant,
            Optional<Instant> clickedAt,
            Optional<Instant> completedAt) {
        if (userId == null) {
            throw new DomainValidationException("Recommendation userId must not be null");
        }
        if (contentId == null) {
            throw new DomainValidationException("Recommendation contentId must not be null");
        }
        if (createdAt == null) {
            throw new DomainValidationException("Recommendation createdAt must not be null");
        }
        if (score == null) {
            throw new DomainValidationException("Recommendation score must not be null");
        }
        if (rankPosition < 1 || rankPosition > MAX_RANK) {
            throw new DomainInvariantException(
                    "Recommendation rankPosition must be in [1, " + MAX_RANK
                            + "], got " + rankPosition);
        }
        if (reason == null) {
            throw new DomainValidationException("Recommendation reason must not be null");
        }
        if (modelVariant == null) {
            throw new DomainValidationException(
                    "Recommendation modelVariant must not be null");
        }
        String trimmedVariant = modelVariant.trim();
        if (trimmedVariant.isEmpty()) {
            throw new DomainValidationException(
                    "Recommendation modelVariant must not be blank");
        }
        if (trimmedVariant.length() > MAX_MODEL_VARIANT_LENGTH) {
            throw new DomainValidationException(
                    "Recommendation modelVariant must be at most "
                            + MAX_MODEL_VARIANT_LENGTH
                            + " characters, got " + trimmedVariant.length());
        }
        if (clickedAt == null) {
            throw new DomainValidationException(
                    "Recommendation clickedAt must not be null"
                            + " (use Optional.empty() for unclicked)");
        }
        if (completedAt == null) {
            throw new DomainValidationException(
                    "Recommendation completedAt must not be null"
                            + " (use Optional.empty() for incomplete)");
        }
        if (clickedAt.isPresent() && clickedAt.get().isBefore(createdAt)) {
            throw new DomainInvariantException(
                    "Recommendation clickedAt (" + clickedAt.get()
                            + ") must be >= createdAt (" + createdAt + ")");
        }
        if (completedAt.isPresent()) {
            if (clickedAt.isEmpty()) {
                throw new DomainInvariantException(
                        "Recommendation completedAt is set but clickedAt is empty"
                                + " — a click must precede a completion");
            }
            if (completedAt.get().isBefore(clickedAt.get())) {
                throw new DomainInvariantException(
                        "Recommendation completedAt (" + completedAt.get()
                                + ") must be >= clickedAt (" + clickedAt.get() + ")");
            }
        }
        this.userId = userId;
        this.contentId = contentId;
        this.createdAt = createdAt;
        this.score = score;
        this.rankPosition = rankPosition;
        this.reason = reason;
        this.modelVariant = trimmedVariant;
        this.clickedAt = clickedAt;
        this.completedAt = completedAt;
    }

    /**
     * Builds a fresh, just-served recommendation. {@code createdAt}
     * comes from {@code clock.instant()}; click and completion
     * timestamps start empty.
     */
    public static Recommendation create(
            UserId userId,
            ContentId contentId,
            RecommendationScore score,
            int rankPosition,
            RecommendationReason reason,
            String modelVariant,
            Clock clock) {
        if (clock == null) {
            throw new DomainValidationException("Recommendation create clock must not be null");
        }
        return new Recommendation(
                userId,
                contentId,
                clock.instant(),
                score,
                rankPosition,
                reason,
                modelVariant,
                Optional.empty(),
                Optional.empty());
    }

    /** Reconstructs a Recommendation from persisted state. */
    public static Recommendation rehydrate(
            UserId userId,
            ContentId contentId,
            Instant createdAt,
            RecommendationScore score,
            int rankPosition,
            RecommendationReason reason,
            String modelVariant,
            Optional<Instant> clickedAt,
            Optional<Instant> completedAt) {
        return new Recommendation(
                userId, contentId, createdAt, score, rankPosition, reason,
                modelVariant, clickedAt, completedAt);
    }

    /**
     * Returns a new {@code Recommendation} with {@code clickedAt} set,
     * preserving every other field. Idempotent on a recommendation
     * already marked clicked: re-clicking with an earlier timestamp is
     * a no-op (we keep the original click), with a later timestamp is
     * rejected (clicks don't move backwards through replay).
     *
     * @throws DomainInvariantException when {@code at} is before
     *     {@code createdAt}, or when this recommendation is already
     *     completed (a completed rec is terminal)
     */
    public Recommendation recordClick(Instant at) {
        if (at == null) {
            throw new DomainValidationException("recordClick at must not be null");
        }
        if (completedAt.isPresent()) {
            throw new DomainInvariantException(
                    "Cannot recordClick: recommendation is already completed");
        }
        if (clickedAt.isPresent()) {
            // Idempotent: keep the earliest click.
            return this;
        }
        return new Recommendation(
                userId, contentId, createdAt, score, rankPosition, reason,
                modelVariant, Optional.of(at), completedAt);
    }

    public UserId userId() {
        return userId;
    }

    public ContentId contentId() {
        return contentId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public RecommendationScore score() {
        return score;
    }

    public int rankPosition() {
        return rankPosition;
    }

    public RecommendationReason reason() {
        return reason;
    }

    public String modelVariant() {
        return modelVariant;
    }

    public Optional<Instant> clickedAt() {
        return clickedAt;
    }

    public Optional<Instant> completedAt() {
        return completedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Recommendation other)) {
            return false;
        }
        return userId.equals(other.userId)
                && contentId.equals(other.contentId)
                && createdAt.equals(other.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, contentId, createdAt);
    }

    @Override
    public String toString() {
        return "Recommendation{userId=" + userId
                + ", contentId=" + contentId
                + ", createdAt=" + createdAt
                + ", rank=" + rankPosition
                + ", score=" + score
                + ", modelVariant=" + modelVariant
                + "}";
    }
}
