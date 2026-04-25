package com.plrs.web.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

/**
 * Request payload for {@code POST /api/content}.
 * {@code ctype} is one of {@code VIDEO|ARTICLE|EXERCISE|QUIZ} — though
 * the use case refuses {@code QUIZ}, the controller accepts the string
 * and lets the domain enum reject unknown values uniformly.
 */
public record CreateContentRequest(
        @NotNull Long topicId,
        @NotBlank String title,
        @NotBlank String ctype,
        @NotBlank String difficulty,
        int estMinutes,
        @NotBlank String url,
        String description,
        Set<String> tags) {}
