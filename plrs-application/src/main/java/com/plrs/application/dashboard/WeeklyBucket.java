package com.plrs.application.dashboard;

/**
 * One bucket of the FR-35 weekly activity sparkline. {@code isoYearWeek}
 * is the Postgres-style ISO week key {@code "YYYY-WW"} (e.g.
 * {@code "2026-17"}), zero-padded so lexicographic ordering matches
 * chronological ordering within a year.
 */
public record WeeklyBucket(String isoYearWeek, int count) {}
