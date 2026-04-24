package com.plrs.domain.content;

import com.plrs.domain.common.DomainValidationException;
import java.util.List;

/**
 * One page of a keyword-search result over {@link Content}. Carries the
 * items in this page plus the cursor metadata the UI needs to render a
 * paginated list (page number / size, total elements, total pages).
 *
 * <p>The underlying query is described on
 * {@link ContentRepository#search(String, int, int)}; this record is the
 * shape the port returns so the use case and the web layer can agree on a
 * single vocabulary without depending on Spring Data's {@code Page} type
 * (which would leak into the domain module).
 *
 * <p>{@link #items} is defensively copied to an immutable list so a
 * {@code SearchPage} cannot be mutated through its original backing
 * collection.
 *
 * <p>Traces to: §3.a (domain-owned query shapes), FR-13 (paginated
 * keyword search).
 */
public record SearchPage(
        List<Content> items,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages) {

    public SearchPage {
        if (items == null) {
            throw new DomainValidationException("SearchPage items must not be null");
        }
        if (pageNumber < 0) {
            throw new DomainValidationException(
                    "SearchPage pageNumber must be >= 0, got " + pageNumber);
        }
        if (pageSize < 1) {
            throw new DomainValidationException(
                    "SearchPage pageSize must be >= 1, got " + pageSize);
        }
        if (totalElements < 0) {
            throw new DomainValidationException(
                    "SearchPage totalElements must be >= 0, got " + totalElements);
        }
        if (totalPages < 0) {
            throw new DomainValidationException(
                    "SearchPage totalPages must be >= 0, got " + totalPages);
        }
        items = List.copyOf(items);
    }
}
