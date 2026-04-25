package com.plrs.web.catalog;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for {@code POST /api/topics}.
 * {@code parentTopicId} is the optional surrogate id of the parent topic;
 * {@code null} produces a root topic.
 */
public record CreateTopicRequest(
        @NotBlank String name, String description, Long parentTopicId) {}
