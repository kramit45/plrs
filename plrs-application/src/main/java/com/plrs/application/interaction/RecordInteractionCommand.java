package com.plrs.application.interaction;

import java.util.Optional;
import java.util.UUID;

/**
 * Command for {@link RecordInteractionUseCase}: log one interaction
 * event. {@code occurredAt} is intentionally absent — the use case
 * stamps it from the injected {@code Clock} so clients can't backdate
 * events to bypass the FR-15 debounce window.
 *
 * <p>{@code eventType} is the string form (e.g. {@code "VIEW"}); the
 * use case routes through {@link com.plrs.domain.interaction.EventType#fromName}
 * for case-sensitive validation.
 */
public record RecordInteractionCommand(
        UUID userId,
        Long contentId,
        String eventType,
        Optional<Integer> dwellSec,
        Optional<Integer> rating,
        Optional<String> clientInfo) {}
