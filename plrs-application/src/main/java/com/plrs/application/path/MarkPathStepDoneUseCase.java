package com.plrs.application.path;

import com.plrs.application.audit.Auditable;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.mastery.UserSkill;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathRepository;
import com.plrs.domain.path.PathId;
import com.plrs.domain.path.StepStatus;
import com.plrs.domain.topic.TopicId;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks one step DONE. If marking this step takes the path's step set
 * to "all DONE/SKIPPED", the use case captures the current mastery
 * snapshot and transitions the path to COMPLETED in the same
 * transaction. The end-snapshot is sourced from the live
 * {@link UserSkillRepository} — by the time this fires, mastery has
 * already been updated by the user's quiz attempts (TX-01).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class MarkPathStepDoneUseCase {

    private final LearnerPathRepository repository;
    private final UserSkillRepository userSkillRepository;
    private final Clock clock;

    public MarkPathStepDoneUseCase(
            LearnerPathRepository repository,
            UserSkillRepository userSkillRepository,
            Clock clock) {
        this.repository = repository;
        this.userSkillRepository = userSkillRepository;
        this.clock = clock;
    }

    @Transactional
    @Auditable(action = "PATH_STEP_DONE", entityType = "learner_path")
    public LearnerPath handle(PathId pathId, int stepOrder) {
        LearnerPath p = repository.findById(pathId).orElseThrow();
        LearnerPath after = p.markStepDone(stepOrder, clock);

        boolean allDone =
                after.steps().stream()
                        .allMatch(
                                s ->
                                        s.status() == StepStatus.DONE
                                                || s.status() == StepStatus.SKIPPED);
        if (allDone) {
            Map<TopicId, MasteryScore> endSnap = new HashMap<>();
            for (UserSkill s : userSkillRepository.findByUser(after.userId())) {
                endSnap.put(s.topicId(), s.mastery());
            }
            after = after.complete(endSnap, clock);
        }
        return repository.update(after);
    }
}
