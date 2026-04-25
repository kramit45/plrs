package com.plrs.domain.content;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.quiz.QuizItem;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Transient pre-persistence form of a {@link Content} of ctype
 * {@link ContentType#QUIZ}. Mirrors {@link ContentDraft} for non-quiz
 * content but additionally carries the {@link QuizItem}s the quiz
 * comprises. The {@link com.plrs.domain.content.ContentRepository#saveQuiz}
 * adapter writes the content row, the {@code quiz_items} rows, and the
 * {@code quiz_item_options} rows in one transaction and returns a
 * fully-populated {@link Content}.
 *
 * <p>Validation mirrors {@link ContentDraft} (title 1..200, est-minutes
 * [1, 600], URL scheme, tag length, etc.) plus quiz-specific rules:
 *
 * <ul>
 *   <li>{@code items} non-null and non-empty (FR-19 — at least one item).
 *   <li>{@code itemOrder} values are unique across the items list.
 * </ul>
 *
 * <p>{@code items} is defensively copied to an immutable list, and
 * {@code tags} to an immutable set, matching the immutability contract
 * of the persisted aggregate.
 *
 * <p>Traces to: §3.b.5.1 (TRG-1 ctype-quiz coupling), §3.b.2.2
 * (Content.newQuiz factory was the original spec; the design evolved
 * into this draft + repository pattern), FR-19 (quiz authoring).
 */
public record QuizContentDraft(
        TopicId topicId,
        String title,
        Difficulty difficulty,
        int estMinutes,
        String url,
        Optional<String> description,
        Set<String> tags,
        Optional<UserId> createdBy,
        AuditFields audit,
        List<QuizItem> items) {

    public QuizContentDraft {
        if (topicId == null) {
            throw new DomainValidationException("QuizContentDraft topicId must not be null");
        }
        if (title == null) {
            throw new DomainValidationException("QuizContentDraft title must not be null");
        }
        if (difficulty == null) {
            throw new DomainValidationException("QuizContentDraft difficulty must not be null");
        }
        if (url == null) {
            throw new DomainValidationException("QuizContentDraft url must not be null");
        }
        if (description == null) {
            throw new DomainValidationException(
                    "QuizContentDraft description must not be null"
                            + " (use Optional.empty() for absent description)");
        }
        if (tags == null) {
            throw new DomainValidationException("QuizContentDraft tags must not be null");
        }
        if (createdBy == null) {
            throw new DomainValidationException(
                    "QuizContentDraft createdBy must not be null"
                            + " (use Optional.empty() for system content)");
        }
        if (audit == null) {
            throw new DomainValidationException("QuizContentDraft audit must not be null");
        }
        if (items == null) {
            throw new DomainValidationException("QuizContentDraft items must not be null");
        }
        if (items.isEmpty()) {
            throw new DomainInvariantException(
                    "QuizContentDraft must have at least one item (FR-19)");
        }
        Set<Integer> seenOrders = new HashSet<>();
        for (QuizItem item : items) {
            if (item == null) {
                throw new DomainValidationException("QuizContentDraft item must not be null");
            }
            if (!seenOrders.add(item.itemOrder())) {
                throw new DomainInvariantException(
                        "QuizContentDraft itemOrders must be unique, duplicate "
                                + item.itemOrder());
            }
        }
        String trimmedTitle = title.trim();
        if (trimmedTitle.isEmpty()) {
            throw new DomainInvariantException("QuizContentDraft title must not be blank");
        }
        if (trimmedTitle.length() > Content.MAX_TITLE_LENGTH) {
            throw new DomainInvariantException(
                    "QuizContentDraft title must be at most " + Content.MAX_TITLE_LENGTH
                            + " characters, got " + trimmedTitle.length());
        }
        if (estMinutes < Content.MIN_EST_MINUTES || estMinutes > Content.MAX_EST_MINUTES) {
            throw new DomainInvariantException(
                    "QuizContentDraft estMinutes must be in ["
                            + Content.MIN_EST_MINUTES + ", " + Content.MAX_EST_MINUTES
                            + "], got " + estMinutes);
        }
        if (!Content.URL_SCHEME.matcher(url).matches()) {
            throw new DomainInvariantException(
                    "QuizContentDraft url must start with http:// or https://, got '"
                            + url + "'");
        }
        title = trimmedTitle;
        tags = Content.normaliseTags(tags);
        items = List.copyOf(items);
    }
}
