package com.plrs.application.dashboard;

import java.time.Instant;

/**
 * One row of the dashboard's "recent completions" table. {@code title}
 * is the snapshot of {@link com.plrs.domain.content.Content#title} at
 * read time; if the content has since been deleted the service
 * substitutes {@code "(deleted)"} so the table can still render.
 */
public record RecentCompletion(Long contentId, String title, Instant completedAt) {}
