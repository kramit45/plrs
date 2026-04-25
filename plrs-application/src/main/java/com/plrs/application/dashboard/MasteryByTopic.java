package com.plrs.application.dashboard;

/**
 * One row of the dashboard's "mastery by topic" card. The view layer
 * renders these as the Chart.js radar's labels + values (FR-35).
 */
public record MasteryByTopic(Long topicId, String topicName, double masteryScore) {}
