package com.plrs.application.content;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.util.Optional;

/**
 * Command for {@link AddPrerequisiteUseCase}: add the directed edge
 * "{@code contentId} requires {@code prereqId}" to the prerequisite DAG.
 *
 * <p>{@code addedBy} is {@link Optional#empty()} when the edge is being
 * created by a system process (seed data, automated import); otherwise
 * the calling user's id flows through for audit.
 */
public record AddPrerequisiteCommand(
        ContentId contentId, ContentId prereqId, Optional<UserId> addedBy) {}
