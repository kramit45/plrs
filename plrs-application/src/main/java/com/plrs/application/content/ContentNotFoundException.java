package com.plrs.application.content;

import com.plrs.domain.content.ContentId;

/**
 * Thrown when a use case asked for content by id but the repository
 * returned {@link java.util.Optional#empty()}. Lives in the application
 * layer (not the domain) because "the use case looked something up and
 * it wasn't there" is an orchestration concern, not a domain invariant.
 *
 * <p>Web layer translates this to HTTP 404 in step 65+.
 */
public class ContentNotFoundException extends RuntimeException {

    private final ContentId contentId;

    public ContentNotFoundException(ContentId contentId) {
        super("Content not found: " + (contentId == null ? "null" : contentId.value()));
        this.contentId = contentId;
    }

    public ContentId contentId() {
        return contentId;
    }
}
