package com.plrs.domain.quiz;

import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.topic.TopicId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One quiz item — a stem (the question), an optional explanation, the
 * topic it tests, and 2..6 answer options exactly one of which is
 * marked correct (FR-19).
 *
 * <p>Weak entity: a {@link QuizItem} has no surrogate id of its own;
 * its persistent identity is the parent {@code content_id} +
 * {@code item_order} pair (see V9 quiz_items DDL). At the domain level
 * we use the natural key {@code (itemOrder, topicId, stem)} for
 * equality, since a domain-side QuizItem is constructed without
 * knowledge of which Content owns it.
 *
 * <p>Invariants enforced by the canonical constructor:
 *
 * <ul>
 *   <li>{@code itemOrder >= 1}.
 *   <li>{@code topicId} non-null.
 *   <li>{@code stem} trimmed length {@code >= 1} (mirrors {@code qitem_stem_nn}).
 *   <li>{@code options.size()} in {@code [2, 6]} (FR-19).
 *   <li>Exactly one option has {@code isCorrect == true} (mirrors the
 *       deferred TRG-2 trigger from §3.b.5.2).
 *   <li>Option orders within the item are unique and positive. They are
 *       <i>not</i> required to be consecutive — instructors can delete
 *       options without renumbering the rest, so {@code [1, 3, 4]} is
 *       valid even though {@code 2} is missing.
 * </ul>
 *
 * <p>The options list returned by {@link #options()} is unmodifiable.
 *
 * <p>Traces to: §3.c.1.3 (quiz_items DDL), §3.b.5.2 (TRG-2 mirror),
 * FR-19 (quiz authoring).
 */
public final class QuizItem {

    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 6;
    private static final int STEM_TRUNCATE = 60;

    private final int itemOrder;
    private final TopicId topicId;
    private final String stem;
    private final Optional<String> explanation;
    private final List<QuizItemOption> options;

    private QuizItem(
            int itemOrder,
            TopicId topicId,
            String stem,
            Optional<String> explanation,
            List<QuizItemOption> options) {
        if (itemOrder < 1) {
            throw new DomainInvariantException(
                    "QuizItem itemOrder must be >= 1, got " + itemOrder);
        }
        if (topicId == null) {
            throw new DomainValidationException("QuizItem topicId must not be null");
        }
        if (stem == null) {
            throw new DomainValidationException("QuizItem stem must not be null");
        }
        if (stem.trim().isEmpty()) {
            throw new DomainInvariantException("QuizItem stem must not be blank");
        }
        if (explanation == null) {
            throw new DomainValidationException(
                    "QuizItem explanation must not be null (use Optional.empty())");
        }
        if (options == null) {
            throw new DomainValidationException("QuizItem options must not be null");
        }
        if (options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            throw new DomainInvariantException(
                    "QuizItem must have between "
                            + MIN_OPTIONS
                            + " and "
                            + MAX_OPTIONS
                            + " options (FR-19), got "
                            + options.size());
        }
        Set<Integer> seenOrders = new HashSet<>();
        int correctCount = 0;
        for (QuizItemOption opt : options) {
            if (opt == null) {
                throw new DomainValidationException("QuizItem option must not be null");
            }
            if (!seenOrders.add(opt.optionOrder())) {
                throw new DomainInvariantException(
                        "QuizItem option orders must be unique, duplicate "
                                + opt.optionOrder());
            }
            if (opt.isCorrect()) {
                correctCount++;
            }
        }
        if (correctCount != 1) {
            throw new DomainInvariantException(
                    "QuizItem must have exactly one correct option (FR-19), found "
                            + correctCount);
        }
        this.itemOrder = itemOrder;
        this.topicId = topicId;
        this.stem = stem;
        this.explanation = explanation;
        this.options = List.copyOf(options);
    }

    /**
     * Constructs a QuizItem. All invariants are enforced; an invalid
     * combination throws {@link DomainInvariantException} (or
     * {@link DomainValidationException} for null fields).
     */
    public static QuizItem of(
            int itemOrder,
            TopicId topicId,
            String stem,
            Optional<String> explanation,
            List<QuizItemOption> options) {
        return new QuizItem(itemOrder, topicId, stem, explanation, options);
    }

    public int itemOrder() {
        return itemOrder;
    }

    public TopicId topicId() {
        return topicId;
    }

    public String stem() {
        return stem;
    }

    public Optional<String> explanation() {
        return explanation;
    }

    /** Unmodifiable view of the options list. */
    public List<QuizItemOption> options() {
        return options;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QuizItem other)) {
            return false;
        }
        return itemOrder == other.itemOrder
                && topicId.equals(other.topicId)
                && stem.equals(other.stem);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemOrder, topicId, stem);
    }

    @Override
    public String toString() {
        String shortStem =
                stem.length() > STEM_TRUNCATE ? stem.substring(0, STEM_TRUNCATE) + "..." : stem;
        return "QuizItem{itemOrder=" + itemOrder
                + ", stem='" + shortStem + "'"
                + ", options=" + options.size()
                + "}";
    }
}
