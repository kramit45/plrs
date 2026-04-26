package com.plrs.application.admin;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Read projection of one row in {@code plrs_ops.config_params}. */
public record ConfigParam(
        String name,
        String value,
        String valueType,
        Optional<String> description,
        Instant updatedAt,
        Optional<UUID> updatedBy) {}
