package com.plrs.application.content;

/**
 * One per-row error from a CSV import. {@code rowNumber} is 1-based and
 * counts the header row, matching what users see in spreadsheet UIs.
 *
 * <p>Traces to: FR-10 (CSV bulk import with per-row error report).
 */
public record ImportRowError(int rowNumber, String message) {}
