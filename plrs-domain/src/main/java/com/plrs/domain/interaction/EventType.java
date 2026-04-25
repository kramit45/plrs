package com.plrs.domain.interaction;

import com.plrs.domain.common.DomainValidationException;
import java.util.Arrays;

/**
 * The five kinds of learner interaction PLRS records. The declared order
 * ({@code VIEW}, {@code COMPLETE}, {@code BOOKMARK}, {@code LIKE},
 * {@code RATE}) is load-bearing: the Postgres
 * {@code interactions_type_enum} CHECK constraint and any UI that
 * renders filters rely on this ordering. Do not reorder without
 * auditing every caller (Flyway migration included).
 *
 * <p>Two helpers expose the per-type rules that {@link InteractionEvent}
 * enforces:
 *
 * <ul>
 *   <li>{@link #allowsDwell()} — only {@code VIEW} and {@code COMPLETE}
 *       carry a {@code dwell_sec} value (interactions
 *       {@code dwell_only_vc} CHECK).
 *   <li>{@link #requiresRating()} — only {@code RATE} carries a
 *       {@code rating} value (interactions {@code rating_iff_rate}
 *       CHECK).
 * </ul>
 *
 * <p>Traces to: §3.c.1.4 (interactions_type_enum), FR-15 (view tracking),
 * FR-16 (interaction events), FR-17 (rating).
 */
public enum EventType {
    VIEW,
    COMPLETE,
    BOOKMARK,
    LIKE,
    RATE;

    public boolean allowsDwell() {
        return this == VIEW || this == COMPLETE;
    }

    public boolean requiresRating() {
        return this == RATE;
    }

    /**
     * Parses an event-type name using a case-sensitive match. Same
     * rationale as {@link com.plrs.domain.user.Role#fromName(String)} and
     * {@link com.plrs.domain.content.ContentType#fromName(String)}.
     *
     * @throws DomainValidationException when {@code name} is null or does
     *     not match any event type
     */
    public static EventType fromName(String name) {
        if (name == null) {
            throw new DomainValidationException(
                    "EventType name must not be null; expected one of "
                            + Arrays.toString(values()));
        }
        for (EventType t : values()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        throw new DomainValidationException(
                "Unknown event type: '" + name + "'; expected one of "
                        + Arrays.toString(values()));
    }
}
