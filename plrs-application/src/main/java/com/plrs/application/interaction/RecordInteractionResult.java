package com.plrs.application.interaction;

/**
 * Outcome of {@link RecordInteractionUseCase#handle}. {@code DEBOUNCED}
 * is FR-15-specific: a VIEW event arrived within 10 minutes of an
 * existing VIEW for the same {@code (user, content)} pair, so the use
 * case did <i>not</i> persist a second row.
 */
public enum RecordInteractionResult {
    RECORDED,
    DEBOUNCED
}
