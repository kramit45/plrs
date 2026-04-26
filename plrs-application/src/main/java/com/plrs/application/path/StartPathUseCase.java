package com.plrs.application.path;

import com.plrs.application.audit.Auditable;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathRepository;
import com.plrs.domain.path.PathId;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transitions a path NOT_STARTED → IN_PROGRESS. */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class StartPathUseCase {

    private final LearnerPathRepository repository;
    private final Clock clock;

    public StartPathUseCase(LearnerPathRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    @Auditable(action = "PATH_STARTED", entityType = "learner_path")
    public LearnerPath handle(PathId pathId) {
        LearnerPath p = repository.findById(pathId).orElseThrow();
        return repository.update(p.start(clock));
    }
}
