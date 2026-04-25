package com.plrs.domain.mastery;

import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link UserSkill} persistence.
 *
 * <p>{@link #upsert} relies on the {@code (user_id, topic_id)}
 * composite PK so a save acts as INSERT-OR-UPDATE; this is the
 * primary write path for the EWMA mastery update in
 * {@code SubmitQuizAttemptUseCase} (step 90).
 *
 * <p>Traces to: §3.c.1.4 (user_skills schema), §3.c.5.7 (EWMA), FR-21.
 */
public interface UserSkillRepository {

    /** Loads the skill row for one (user, topic) pair. */
    Optional<UserSkill> find(UserId userId, TopicId topicId);

    /** Persists or updates the skill row; returns the persisted form. */
    UserSkill upsert(UserSkill skill);

    /** Lists every skill row for a user — the per-user mastery vector. */
    List<UserSkill> findByUser(UserId userId);
}
