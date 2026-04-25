package com.plrs.web.catalog;

import jakarta.validation.constraints.NotNull;

/** Body for {@code POST /api/content/{id}/prerequisites}. */
public record AddPrereqRequest(@NotNull Long prereqContentId) {}
