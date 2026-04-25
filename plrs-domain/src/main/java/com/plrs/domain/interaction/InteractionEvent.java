package com.plrs.domain.interaction;

import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One immutable learner-interaction event. Mirrors the per-row contract
 * of {@code plrs_ops.interactions} (§3.c.1.4) and defends three CHECK
 * constraints from inside the domain so the database is the last line of
 * defence, not the only one:
 *
 * <ul>
 *   <li>{@code interactions_type_enum} — covered by {@link EventType}'s
 *       closed enumeration.
 *   <li>{@code rating_iff_rate} — {@code rating} is present iff the
 *       event type is {@link EventType#RATE}.
 *   <li>{@code dwell_only_vc} — {@code dwellSec} is permitted only for
 *       {@link EventType#VIEW} or {@link EventType#COMPLETE}, and must
 *       be {@code >= 0} when present.
 * </ul>
 *
 * <p>Five named factories ({@link #view}, {@link #complete},
 * {@link #bookmark}, {@link #like}, {@link #rate}) make call-site intent
 * obvious — the canonical constructor stays {@code private} so all
 * instantiation flows through the same validation. The factories' own
 * signatures already exclude impossible combinations (e.g. {@code rate}
 * requires a non-null {@link Rating}; {@code bookmark} has no dwell
 * parameter), so most invariant breaches are caught at compile time;
 * the canonical constructor catches the rest (mainly reflection-driven
 * or persistence-rehydration paths).
 *
 * <p>{@link #toString()} deliberately <b>excludes</b> {@link #clientInfo}
 * since that field can carry IP addresses and user-agent strings —
 * undesirable in log lines that aggregate widely.
 *
 * <p>Equality is based on the natural key {@code (userId, contentId,
 * occurredAt, eventType)}: per §3.c.1.4 this combination is the
 * application-level dedup signal (also re-enforced by the 10-minute
 * debounce in step 71's use case). Two events that disagree only on
 * {@code dwellSec} or {@code clientInfo} are treated as equal.
 *
 * <p>Traces to: §3.c.1.4 (interactions schema + three CHECK constraints),
 * FR-15 (view tracking), FR-16 (interactions), FR-17 (rating).
 */
public final class InteractionEvent {

    static final int MAX_CLIENT_INFO_LENGTH = 200;

    private final UserId userId;
    private final ContentId contentId;
    private final Instant occurredAt;
    private final EventType eventType;
    private final Optional<Integer> dwellSec;
    private final Optional<Rating> rating;
    private final Optional<String> clientInfo;

    private InteractionEvent(
            UserId userId,
            ContentId contentId,
            Instant occurredAt,
            EventType eventType,
            Optional<Integer> dwellSec,
            Optional<Rating> rating,
            Optional<String> clientInfo) {
        if (userId == null) {
            throw new DomainValidationException("InteractionEvent userId must not be null");
        }
        if (contentId == null) {
            throw new DomainValidationException("InteractionEvent contentId must not be null");
        }
        if (occurredAt == null) {
            throw new DomainValidationException("InteractionEvent occurredAt must not be null");
        }
        if (eventType == null) {
            throw new DomainValidationException("InteractionEvent eventType must not be null");
        }
        if (dwellSec == null) {
            throw new DomainValidationException(
                    "InteractionEvent dwellSec must not be null"
                            + " (use Optional.empty() for absent dwell)");
        }
        if (rating == null) {
            throw new DomainValidationException(
                    "InteractionEvent rating must not be null"
                            + " (use Optional.empty() for non-RATE events)");
        }
        if (clientInfo == null) {
            throw new DomainValidationException(
                    "InteractionEvent clientInfo must not be null"
                            + " (use Optional.empty() for absent client info)");
        }
        if (dwellSec.isPresent()) {
            if (!eventType.allowsDwell()) {
                throw new DomainValidationException(
                        "dwell_sec only permitted for VIEW/COMPLETE, got " + eventType);
            }
            if (dwellSec.get() < 0) {
                throw new DomainValidationException(
                        "dwell_sec must be >= 0, got " + dwellSec.get());
            }
        }
        if (rating.isPresent() != eventType.requiresRating()) {
            throw new DomainValidationException(
                    "rating must be present iff eventType is RATE; eventType="
                            + eventType
                            + ", ratingPresent="
                            + rating.isPresent());
        }
        if (clientInfo.isPresent() && clientInfo.get().length() > MAX_CLIENT_INFO_LENGTH) {
            throw new DomainValidationException(
                    "clientInfo must be at most " + MAX_CLIENT_INFO_LENGTH
                            + " characters, got " + clientInfo.get().length());
        }
        this.userId = userId;
        this.contentId = contentId;
        this.occurredAt = occurredAt;
        this.eventType = eventType;
        this.dwellSec = dwellSec;
        this.rating = rating;
        this.clientInfo = clientInfo;
    }

    public static InteractionEvent view(
            UserId userId,
            ContentId contentId,
            Instant occurredAt,
            Optional<Integer> dwellSec,
            Optional<String> clientInfo) {
        return new InteractionEvent(
                userId,
                contentId,
                occurredAt,
                EventType.VIEW,
                dwellSec,
                Optional.empty(),
                clientInfo);
    }

    public static InteractionEvent complete(
            UserId userId,
            ContentId contentId,
            Instant occurredAt,
            Optional<Integer> dwellSec,
            Optional<String> clientInfo) {
        return new InteractionEvent(
                userId,
                contentId,
                occurredAt,
                EventType.COMPLETE,
                dwellSec,
                Optional.empty(),
                clientInfo);
    }

    public static InteractionEvent bookmark(
            UserId userId,
            ContentId contentId,
            Instant occurredAt,
            Optional<String> clientInfo) {
        return new InteractionEvent(
                userId,
                contentId,
                occurredAt,
                EventType.BOOKMARK,
                Optional.empty(),
                Optional.empty(),
                clientInfo);
    }

    public static InteractionEvent like(
            UserId userId,
            ContentId contentId,
            Instant occurredAt,
            Optional<String> clientInfo) {
        return new InteractionEvent(
                userId,
                contentId,
                occurredAt,
                EventType.LIKE,
                Optional.empty(),
                Optional.empty(),
                clientInfo);
    }

    public static InteractionEvent rate(
            UserId userId,
            ContentId contentId,
            Instant occurredAt,
            Rating rating,
            Optional<String> clientInfo) {
        if (rating == null) {
            throw new DomainValidationException(
                    "rate(...) requires a non-null Rating; pass an explicit Rating value");
        }
        return new InteractionEvent(
                userId,
                contentId,
                occurredAt,
                EventType.RATE,
                Optional.empty(),
                Optional.of(rating),
                clientInfo);
    }

    public UserId userId() {
        return userId;
    }

    public ContentId contentId() {
        return contentId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public EventType eventType() {
        return eventType;
    }

    public Optional<Integer> dwellSec() {
        return dwellSec;
    }

    public Optional<Rating> rating() {
        return rating;
    }

    public Optional<String> clientInfo() {
        return clientInfo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InteractionEvent other)) {
            return false;
        }
        return userId.equals(other.userId)
                && contentId.equals(other.contentId)
                && occurredAt.equals(other.occurredAt)
                && eventType == other.eventType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, contentId, occurredAt, eventType);
    }

    @Override
    public String toString() {
        return "InteractionEvent{"
                + "userId=" + userId
                + ", contentId=" + contentId
                + ", occurredAt=" + occurredAt
                + ", eventType=" + eventType
                + "}";
    }
}
