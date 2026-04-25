package com.plrs.web.catalog;

import java.util.List;

/** Paginated search response shape for {@code GET /api/content/search}. */
public record ContentSearchResponse(
        List<ContentSummary> items,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages) {}
