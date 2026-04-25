package com.plrs.application.dashboard;

import java.util.List;

/**
 * Read-only projection rendered by the {@code GET /dashboard} controller.
 * Aggregates the three FR-35 sections so the view layer can emit a
 * single Thymeleaf model attribute and Chart.js consume the mastery
 * arrays without further server-side mapping.
 */
public record StudentDashboardView(
        List<MasteryByTopic> top6Mastery,
        List<RecentCompletion> recentCompletes,
        List<RecentAttempt> recentAttempts) {}
