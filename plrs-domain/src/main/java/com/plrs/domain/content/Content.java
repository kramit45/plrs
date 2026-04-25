package com.plrs.domain.content;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.quiz.AnswerSubmission;
import com.plrs.domain.quiz.PerItemFeedback;
import com.plrs.domain.quiz.QuizAttempt;
import com.plrs.domain.quiz.QuizItem;
import com.plrs.domain.quiz.QuizItemOption;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final List<QuizItem> quizItems;

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
            AuditFields audit,
            List<QuizItem> quizItems) {
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
        // QUIZ ↔ items coupling per §3.b.5.1 (TRG-1 mirror in domain).
        // QUIZ ctype requires at least one item; non-QUIZ must carry no items.
        // Null items lists are coerced to empty for non-QUIZ for caller
        // ergonomics; QUIZ ctype always requires the caller to pass items.
        List<QuizItem> coercedItems = quizItems == null ? List.of() : quizItems;
        if (ctype == ContentType.QUIZ) {
            if (coercedItems.isEmpty()) {
                throw new DomainInvariantException(
                        "Content of ctype=QUIZ must carry at least one QuizItem");
            }
        } else {
            if (!coercedItems.isEmpty()) {
                throw new DomainInvariantException(
                        "Content of ctype=" + ctype + " must not carry quiz items, got "
                                + coercedItems.size());
            }
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
        this.quizItems = List.copyOf(coercedItems);
    }

    /**
     * Reconstructs a non-quiz content item from persisted state. Accepts
     * any {@link ContentType} <i>except</i> {@link ContentType#QUIZ} —
     * for QUIZ, callers must use the {@link #rehydrate(ContentId, TopicId,
     * String, ContentType, Difficulty, int, String, Optional, Set,
     * Optional, AuditFields, List)} overload that takes the item list.
     * (Calling this 11-arg form with {@code ctype=QUIZ} would throw
     * because empty items violate the QUIZ-coupling invariant.)
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
        return rehydrate(
                id,
                topicId,
                title,
                ctype,
                difficulty,
                estMinutes,
                url,
                description,
                tags,
                createdBy,
                audit,
                List.of());
    }

    /**
     * Reconstructs a content item from persisted state, including QUIZ
     * items. The QUIZ-coupling invariant (items present iff
     * {@code ctype=QUIZ}) is checked by the canonical constructor.
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
            AuditFields audit,
            List<QuizItem> quizItems) {
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
                audit,
                quizItems);
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

    /**
     * Unmodifiable view of the quiz items. Empty list for non-QUIZ content;
     * never null.
     */
    public List<QuizItem> quizItems() {
        return quizItems;
    }

    /**
     * Server-authoritative quiz scoring (FR-20). Compares each
     * {@link AnswerSubmission} against the correct option held by the
     * matching {@link QuizItem} and returns a fully-validated
     * {@link QuizAttempt}.
     *
     * <p>Scoring rules:
     *
     * <ul>
     *   <li>Score = {@code correct * 100 / total}, rounded HALF_UP to 2
     *       decimal places — matches the {@code NUMERIC(5,2)} column.
     *   <li>Items not in the submission are scored as incorrect; the
     *       per-item feedback records {@code selectedOptionOrder = 0}
     *       (the sentinel for "blank").
     *   <li>Submissions referencing an unknown {@code item_order} throw
     *       {@link DomainValidationException} — defensive shape check.
     *   <li>Per-topic weights = {@code items_in_topic / total} at scale
     *       3, with the rounding residue absorbed by the topic with the
     *       largest weight so the sum is always exactly {@code 1.000}.
     * </ul>
     *
     * <p>Pure function: no I/O, no mutation. The caller (use case) is
     * responsible for persisting the returned attempt.
     *
     * <p>Traces to: §3.c.6.3 (QuizAttemptService.submit), §3.c.5.7
     * (per-topic weights for EWMA), FR-20 (scoring contract).
     *
     * @throws DomainInvariantException if {@code ctype != QUIZ}
     * @throws DomainValidationException if any submission references an
     *     unknown {@code item_order}
     */
    public QuizAttempt score(
            UserId userId, List<AnswerSubmission> answers, Clock clock) {
        if (ctype != ContentType.QUIZ) {
            throw new DomainInvariantException(
                    "Content.score requires ctype=QUIZ, got " + ctype);
        }
        if (userId == null) {
            throw new DomainValidationException("Content.score userId must not be null");
        }
        if (answers == null) {
            throw new DomainValidationException("Content.score answers must not be null");
        }
        if (clock == null) {
            throw new DomainValidationException("Content.score clock must not be null");
        }

        Set<Integer> validItemOrders = new HashSet<>();
        for (QuizItem item : quizItems) {
            validItemOrders.add(item.itemOrder());
        }
        for (AnswerSubmission ans : answers) {
            if (!validItemOrders.contains(ans.itemOrder())) {
                throw new DomainValidationException(
                        "Answer references unknown item_order: " + ans.itemOrder());
            }
        }

        int total = quizItems.size();
        int correct = 0;
        List<PerItemFeedback> feedback = new ArrayList<>(total);
        Map<TopicId, Integer> topicCounts = new HashMap<>();

        for (QuizItem item : quizItems) {
            topicCounts.merge(item.topicId(), 1, Integer::sum);
            int correctOptionOrder =
                    item.options().stream()
                            .filter(QuizItemOption::isCorrect)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new DomainInvariantException(
                                                    "QuizItem missing a correct option"))
                            .optionOrder();
            AnswerSubmission ans =
                    answers.stream()
                            .filter(a -> a.itemOrder() == item.itemOrder())
                            .findFirst()
                            .orElse(null);
            int selected = ans == null ? 0 : ans.selectedOptionOrder();
            boolean isCorrect = ans != null && ans.selectedOptionOrder() == correctOptionOrder;
            if (isCorrect) {
                correct++;
            }
            feedback.add(
                    new PerItemFeedback(
                            item.itemOrder(),
                            selected,
                            correctOptionOrder,
                            isCorrect,
                            item.topicId()));
        }

        BigDecimal score =
                BigDecimal.valueOf(correct)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);

        Map<TopicId, BigDecimal> weights = new HashMap<>();
        for (Map.Entry<TopicId, Integer> e : topicCounts.entrySet()) {
            weights.put(
                    e.getKey(),
                    BigDecimal.valueOf(e.getValue())
                            .divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP));
        }
        // Push the rounding residue onto the topic with the largest weight so
        // the sum is exactly 1.000 (avoids 0.999 / 1.001 drift on n=3, n=7, …).
        BigDecimal sum = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal residue = BigDecimal.ONE.setScale(3).subtract(sum);
        if (residue.compareTo(BigDecimal.ZERO) != 0) {
            TopicId largest =
                    weights.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .orElseThrow()
                            .getKey();
            weights.put(largest, weights.get(largest).add(residue));
        }

        return new QuizAttempt(
                userId,
                this.id,
                score,
                correct,
                total,
                feedback,
                weights,
                Instant.now(clock));
    }

    public Optional<UserId> createdBy() {
        return createdBy;
    }

    public AuditFields audit() {
        return audit;
    }

    /**
     * Validates that adding the edge {@code (this, prereq)} to the
     * prerequisite DAG is legal. Throws {@link CycleDetectedException}
     * if {@code prereq} is this content (self-edge), or if the supplied
     * {@link PrerequisiteCheckingRepository} reports that an existing
     * path from {@code prereq} back to this content would close a cycle.
     *
     * <p>The method is read-only — it does not persist the edge. The
     * caller (an application-layer use case) is responsible for writing
     * the edge after this passes. Splitting validation from persistence
     * keeps the aggregate framework-free; the caller can compose this
     * with whatever transactional boundary it needs.
     *
     * <p>The "repository passed in" pattern (§3.b.2.3) avoids holding a
     * reference to the repository on the aggregate while still letting
     * the aggregate own the policy. The narrower
     * {@link PrerequisiteCheckingRepository} interface — distinct from
     * {@link ContentRepository} — limits what the aggregate can ask of
     * its caller.
     *
     * <p>Traces to: §3.b.2.3 (no-cycle invariant), §2.e.2.5 (CFD-4),
     * FR-09 (prerequisite tracking).
     *
     * @throws DomainValidationException when {@code prereq} is null
     * @throws CycleDetectedException when the edge would close a cycle
     */
    public void canAddPrerequisite(
            ContentId prereq, PrerequisiteCheckingRepository checkingRepo) {
        if (prereq == null) {
            throw new DomainValidationException("prereq is required");
        }
        if (checkingRepo == null) {
            throw new DomainValidationException("checkingRepo is required");
        }
        if (this.id.equals(prereq)) {
            throw new CycleDetectedException(this.id, prereq, List.of(this.id));
        }
        List<ContentId> cyclePath = checkingRepo.findCyclePath(this.id, prereq);
        if (cyclePath != null && !cyclePath.isEmpty()) {
            throw new CycleDetectedException(this.id, prereq, cyclePath);
        }
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
