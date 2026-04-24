package com.plrs.domain.content;

import com.plrs.domain.common.DomainValidationException;
import java.util.Arrays;

/**
 * The four kinds of content a topic can carry. The declared order
 * ({@code VIDEO}, {@code ARTICLE}, {@code EXERCISE}, {@code QUIZ}) is
 * load-bearing: the Postgres {@code content_ctype_enum} CHECK constraint
 * and any UI that renders filters rely on this ordering. Do not reorder
 * without auditing every caller (Flyway migration included).
 *
 * <p>{@code QUIZ} is the most structurally distinct variant — it carries
 * {@code quiz_items} rows and has its own submission/grading flow — so
 * content-type-specific handling sits on the {@code Content} aggregate and
 * later quiz use cases, not on this enum.
 *
 * <p>Traces to: §3.c.1.3 (content_ctype_enum), FR-08 (content catalogue).
 */
public enum ContentType {
    VIDEO,
    ARTICLE,
    EXERCISE,
    QUIZ;

    /**
     * Parses a content-type name using a case-sensitive match. The
     * case-sensitivity is deliberate — it forces callers and serialisation
     * formats to agree on the canonical spelling rather than silently
     * accepting "quiz" and "Quiz" as synonyms, which would scatter
     * normalisation logic across the codebase.
     *
     * @throws DomainValidationException when {@code name} is null or does
     *     not match any content type; the message lists the valid values
     */
    public static ContentType fromName(String name) {
        if (name == null) {
            throw new DomainValidationException(
                    "ContentType name must not be null; expected one of "
                            + Arrays.toString(values()));
        }
        for (ContentType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        throw new DomainValidationException(
                "Unknown content type: '" + name + "'; expected one of "
                        + Arrays.toString(values()));
    }
}
