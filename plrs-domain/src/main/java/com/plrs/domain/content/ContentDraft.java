package com.plrs.domain.content;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.util.Optional;
import java.util.Set;

/**
 * Transient form of {@link Content} prior to persistence. Carries
 * everything except the id — the repository's {@code save} method assigns
 * the {@code BIGSERIAL} id (§3.c.4) and returns a fully-populated
 * {@link Content}.
 *
 * <p>Validation mirrors {@link Content}'s canonical constructor with one
 * extra rule: {@link ContentType#QUIZ} is refused here because quiz
 * authoring routes through the dedicated {@code Content.newQuiz} factory
 * (deferred to step 79), which also materialises the associated
 * {@code quiz_items}. Trying to build a quiz through this draft would
 * create a content row with no items — a broken aggregate. Non-quiz
 * content types ({@code VIDEO}, {@code ARTICLE}, {@code EXERCISE}) pass
 * through normally.
 *
 * <p>{@link #tags} is defensively copied to an immutable set; the trimmed
 * form of each tag is what persists, so callers cannot later poke at the
 * original collection and change the draft's notion of "tags".
 *
 * <p>Traces to: §3.c.1.3 (content DDL), §3.c.4 (BIGSERIAL surrogate),
 * FR-08 (content catalogue). Quiz authoring path: step 79.
 */
public record ContentDraft(
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

    public ContentDraft {
        if (topicId == null) {
            throw new DomainValidationException("ContentDraft topicId must not be null");
        }
        if (title == null) {
            throw new DomainValidationException("ContentDraft title must not be null");
        }
        if (ctype == null) {
            throw new DomainValidationException("ContentDraft ctype must not be null");
        }
        if (difficulty == null) {
            throw new DomainValidationException("ContentDraft difficulty must not be null");
        }
        if (url == null) {
            throw new DomainValidationException("ContentDraft url must not be null");
        }
        if (description == null) {
            throw new DomainValidationException(
                    "ContentDraft description must not be null"
                            + " (use Optional.empty() for absent description)");
        }
        if (tags == null) {
            throw new DomainValidationException("ContentDraft tags must not be null");
        }
        if (createdBy == null) {
            throw new DomainValidationException(
                    "ContentDraft createdBy must not be null"
                            + " (use Optional.empty() for system content)");
        }
        if (audit == null) {
            throw new DomainValidationException("ContentDraft audit must not be null");
        }
        if (ctype == ContentType.QUIZ) {
            throw new DomainInvariantException(
                    "ContentDraft refuses QUIZ ctype; use Content.newQuiz for QUIZ content"
                            + " (deferred to step 79)");
        }
        String trimmedTitle = title.trim();
        if (trimmedTitle.isEmpty()) {
            throw new DomainInvariantException("ContentDraft title must not be blank");
        }
        if (trimmedTitle.length() > Content.MAX_TITLE_LENGTH) {
            throw new DomainInvariantException(
                    "ContentDraft title must be at most " + Content.MAX_TITLE_LENGTH
                            + " characters, got " + trimmedTitle.length());
        }
        if (estMinutes < Content.MIN_EST_MINUTES || estMinutes > Content.MAX_EST_MINUTES) {
            throw new DomainInvariantException(
                    "ContentDraft estMinutes must be in ["
                            + Content.MIN_EST_MINUTES + ", " + Content.MAX_EST_MINUTES
                            + "], got " + estMinutes);
        }
        if (!Content.URL_SCHEME.matcher(url).matches()) {
            throw new DomainInvariantException(
                    "ContentDraft url must start with http:// or https://, got '" + url + "'");
        }
        title = trimmedTitle;
        tags = Content.normaliseTags(tags);
    }
}
