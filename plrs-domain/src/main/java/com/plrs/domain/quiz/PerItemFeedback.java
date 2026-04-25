package com.plrs.domain.quiz;

import com.plrs.domain.topic.TopicId;

/**
 * Per-item slice of a {@link QuizAttempt}: which option the student
 * picked, which one was correct, whether the pick matched, and the
 * topic the item tested (used to weight the EWMA mastery update in
 * step 90).
 *
 * <p>{@code selectedOptionOrder == 0} signals an unanswered item — the
 * student left it blank and the scoring path treats blank as incorrect.
 */
public record PerItemFeedback(
        int itemOrder,
        int selectedOptionOrder,
        int correctOptionOrder,
        boolean isCorrect,
        TopicId topicId) {}
