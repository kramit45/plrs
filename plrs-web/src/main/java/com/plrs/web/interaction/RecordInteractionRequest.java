package com.plrs.web.interaction;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload for {@code POST /api/interactions} and
 * {@code POST /web-api/interactions}.
 *
 * <p>Bean-validation handles the shape (required fields, rating range,
 * client-info length); the use case enforces cross-field rules
 * (e.g. {@code dwellSec} only on VIEW/COMPLETE).
 */
public record RecordInteractionRequest(
        @NotNull Long contentId,
        @NotBlank String eventType,
        Integer dwellSec,
        @Min(1) @Max(5) Integer rating,
        @Size(max = 200) String clientInfo) {}
