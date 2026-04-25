package com.plrs.application.topic;

/**
 * Thrown when a {@link CreateTopicUseCase} command names a topic that
 * already exists (the {@code topics_name_uk} unique constraint).
 *
 * <p>Web layer translates this to HTTP 409 in step 65+.
 */
public class TopicAlreadyExistsException extends RuntimeException {

    private final String name;

    public TopicAlreadyExistsException(String name) {
        super("Topic already exists: " + name);
        this.name = name;
    }

    public String name() {
        return name;
    }
}
