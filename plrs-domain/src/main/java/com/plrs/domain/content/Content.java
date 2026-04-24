package com.plrs.domain.content;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Aggregate root representing a single piece of learnable content — a
 * video, article, exercise, or quiz tied to exactly one topic. Authors a
 * URL, a difficulty label, an estimated time-to-consume, and an optional
 * tag set the recommender can filter on.
 *
 * <p>The class is deliberately immutable: fields are {@code private final}
 * and there are no setters. Updates (re-tagging, re-rating difficulty,
 * moving across topics) are out of Iteration 2 scope.
 *
 * <p>Two construction paths:
 *
 * <ul>
 *   <li>Fresh authoring uses {@link ContentDraft} — the record enforces the
 *       full invariant set <b>and</b> refuses {@link ContentType#QUIZ},
 *       pushing quiz creation through the dedicated {@code Content.newQuiz}
 *       factory that lands in step 79. The repository's save path then
 *       assigns the {@code BIGSERIAL} id and rehydrates.
 *   <li>{@link #rehydrate} — the persistence path. Accepts <b>any</b>
 *       {@link ContentType} including {@code QUIZ} because quiz rows
 *       persisted by the future {@code Content.newQuiz} path must be
 *       loadable alongside the rest.
 * </ul>
 *
 * <p>The canonical constructor therefore validates everything <i>except</i>
 * the "no QUIZ" rule — that rule is the draft's business, and the domain
 * already mirrors the DB enum {@code content_ctype_enum} which accepts
 * QUIZ freely. See {@link ContentDraft} for the stricter authoring path.
 *
 * <p>Invariants enforced here:
 *
 * <ul>
 *   <li>{@code id}, {@code topicId}, {@code title}, {@code ctype},
 *       {@code difficulty}, {@code url}, {@code tags}, {@code createdBy},
 *       {@code audit} are all non-null ({@link DomainValidationException}).
 *       {@code description} is the sole nullable field.
 *   <li>{@code title} trimmed is non-blank and 1..200 characters
 *       ({@link DomainInvariantException}).
 *   <li>{@code estMinutes} is in the inclusive range {@code [1, 600]}
 *       matching the DB {@code content_est_minutes_range} CHECK
 *       ({@link DomainInvariantException}).
 *   <li>{@code url} starts with {@code http://} or {@code https://}
 *       ({@link DomainInvariantException}); no other schemes are accepted.
 *   <li>Each tag, after trimming, is non-blank and at most 60 characters
 *       ({@link DomainInvariantException}).
 * </ul>
 *
 * <p>Equality is <b>identity-based</b>: two content instances are equal iff
 * their {@link ContentId}s are equal — the conventional aggregate-root
 * equality semantic. {@link #toString()} deliberately <b>excludes</b> the
 * URL: URLs can be long, can carry pre-signed tokens, and can pollute log
 * lines in ways that are neither useful for debugging nor safe for
 * aggregation.
 *
 * <p>Traces to: §3.c.1.3 (content DDL), §3.b.2.2 (aggregate constructor
 * invariants), FR-08 (content catalogue). Quiz construction path: step 79.
 */
public final class Content {

    static final int MAX_TITLE_LENGTH = 200;
    static final int MIN_EST_MINUTES = 1;
    static final int MAX_EST_MINUTES = 600;
    static final int MAX_TAG_LENGTH = 60;
    static final Pattern URL_SCHEME = Pattern.compile("^https?://.+");

    private final ContentId id;
    private final TopicId topicId;
    private final String title;
    private final ContentType ctype;
    private final Difficulty difficulty;
    private final int estMinutes;
    private final String url;
    private final String description;
    private final Set<String> tags;
    private final Optional<UserId> createdBy;
    private final AuditFields audit;

    private Content(
            ContentId id,
            TopicId topicId,
            String title,
            ContentType ctype,
            Difficulty difficulty,
            int estMinutes,
            String url,
            String description,
            Set<String> tags,
            Optional<UserId> createdBy,
            AuditFields audit) {
        if (id == null) {
            throw new DomainValidationException("Content id must not be null");
        }
        if (topicId == null) {
            throw new DomainValidationException("Content topicId must not be null");
        }
        if (title == null) {
            throw new DomainValidationException("Content title must not be null");
        }
        if (ctype == null) {
            throw new DomainValidationException("Content ctype must not be null");
        }
        if (difficulty == null) {
            throw new DomainValidationException("Content difficulty must not be null");
        }
        if (url == null) {
            throw new DomainValidationException("Content url must not be null");
        }
        if (tags == null) {
            throw new DomainValidationException("Content tags must not be null");
        }
        if (createdBy == null) {
            throw new DomainValidationException(
                    "Content createdBy must not be null (use Optional.empty() for system content)");
        }
        if (audit == null) {
            throw new DomainValidationException("Content audit must not be null");
        }
        String trimmedTitle = title.trim();
        if (trimmedTitle.isEmpty()) {
            throw new DomainInvariantException("Content title must not be blank");
        }
        if (trimmedTitle.length() > MAX_TITLE_LENGTH) {
            throw new DomainInvariantException(
                    "Content title must be at most " + MAX_TITLE_LENGTH
                            + " characters, got " + trimmedTitle.length());
        }
        if (estMinutes < MIN_EST_MINUTES || estMinutes > MAX_EST_MINUTES) {
            throw new DomainInvariantException(
                    "Content estMinutes must be in [" + MIN_EST_MINUTES + ", " + MAX_EST_MINUTES
                            + "], got " + estMinutes);
        }
        if (!URL_SCHEME.matcher(url).matches()) {
            throw new DomainInvariantException(
                    "Content url must start with http:// or https://, got '" + url + "'");
        }
        this.id = id;
        this.topicId = topicId;
        this.title = trimmedTitle;
        this.ctype = ctype;
        this.difficulty = difficulty;
        this.estMinutes = estMinutes;
        this.url = url;
        this.description = description;
        this.tags = normaliseTags(tags);
        this.createdBy = createdBy;
        this.audit = audit;
    }

    /**
     * Reconstructs a content item from persisted state. Accepts any
     * {@link ContentType} (including {@link ContentType#QUIZ}); the
     * draft-path validation that refuses QUIZ lives on
     * {@link ContentDraft}, not here. All other invariants still run.
     */
    public static Content rehydrate(
            ContentId id,
            TopicId topicId,
            String title,
            ContentType ctype,
            Difficulty difficulty,
            int estMinutes,
            String url,
            Optional<String> description,
            Set<String> tags,
            Optional<UserId> createdBy,
            AuditFields audit) {
        if (description == null) {
            throw new DomainValidationException(
                    "Content description must not be null"
                            + " (use Optional.empty() for absent description)");
        }
        return new Content(
                id,
                topicId,
                title,
                ctype,
                difficulty,
                estMinutes,
                url,
                description.orElse(null),
                tags,
                createdBy,
                audit);
    }

    static Set<String> normaliseTags(Set<String> tags) {
        Set<String> normalised = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null) {
                throw new DomainValidationException("Content tag must not be null");
            }
            String t = tag.trim();
            if (t.isEmpty()) {
                throw new DomainInvariantException("Content tag must not be blank");
            }
            if (t.length() > MAX_TAG_LENGTH) {
                throw new DomainInvariantException(
                        "Content tag must be at most " + MAX_TAG_LENGTH
                                + " characters, got " + t.length() + " ('" + t + "')");
            }
            normalised.add(t);
        }
        return Set.copyOf(normalised);
    }

    public ContentId id() {
        return id;
    }

    public TopicId topicId() {
        return topicId;
    }

    public String title() {
        return title;
    }

    public ContentType ctype() {
        return ctype;
    }

    public Difficulty difficulty() {
        return difficulty;
    }

    public int estMinutes() {
        return estMinutes;
    }

    public String url() {
        return url;
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    public Set<String> tags() {
        return tags;
    }

    public Optional<UserId> createdBy() {
        return createdBy;
    }

    public AuditFields audit() {
        return audit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Content other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Content{id=" + id
                + ", title='" + title + "'"
                + ", ctype=" + ctype
                + ", topicId=" + topicId
                + "}";
    }
}
