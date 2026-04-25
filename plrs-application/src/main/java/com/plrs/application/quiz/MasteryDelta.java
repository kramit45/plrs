package com.plrs.application.quiz;

import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.topic.TopicId;

/**
 * The mastery change for one topic produced by a quiz submission.
 * The use case (step 90) returns one delta per topic in the quiz's
 * topic-weights map; the web layer renders them so the student sees
 * how the attempt moved their mastery vector.
 */
public record MasteryDelta(TopicId topicId, MasteryScore oldMastery, MasteryScore newMastery) {}
