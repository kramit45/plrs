package com.plrs.web.path;

import com.plrs.application.path.AbandonPathUseCase;
import com.plrs.application.path.GeneratePathUseCase;
import com.plrs.application.path.MarkPathStepDoneUseCase;
import com.plrs.application.path.PausePathUseCase;
import com.plrs.application.path.PathPlanner;
import com.plrs.application.path.ResumePathUseCase;
import com.plrs.application.path.StartPathUseCase;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathDraft;
import com.plrs.domain.path.LearnerPathRepository;
import com.plrs.domain.path.PathId;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for FR-31..FR-34 learner-path operations.
 *
 * <p>{@code GET /api/learning-path?targetTopicId=…&persist=true} runs
 * the planner; with {@code persist=true} the result is persisted (and
 * the prior active for that target is superseded per TX-10), with
 * {@code persist=false} (default) the response is a preview only —
 * useful for the dashboard's "what would my path look like?" UI.
 *
 * <p>{@code POST /api/learning-path/{pathId}/(start|pause|resume|abandon)}
 * are 204 transitions. {@code POST /…/steps/{stepOrder}/done} marks a
 * single step done, auto-completing the path when the step set is all
 * done/skipped.
 *
 * <p>{@code GET /api/learning-path/active} returns the learner's
 * currently-active paths across all targets — backs the dashboard
 * card.
 *
 * <p>Auth: STUDENT only. The principal name is the user's UUID; we do
 * not honour an explicit {@code userId} query param so a STUDENT
 * cannot inspect another student's paths from this surface (admin
 * needs are deferred to Iter 5).
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: FR-31..FR-34, §3.b.4.3.
 */
@RestController
@RequestMapping("/api/learning-path")
@PreAuthorize("hasRole('STUDENT')")
@ConditionalOnProperty(name = "spring.datasource.url")
public class PathController {

    private final PathPlanner planner;
    private final GeneratePathUseCase generateUseCase;
    private final StartPathUseCase startUseCase;
    private final PausePathUseCase pauseUseCase;
    private final ResumePathUseCase resumeUseCase;
    private final AbandonPathUseCase abandonUseCase;
    private final MarkPathStepDoneUseCase markStepDoneUseCase;
    private final LearnerPathRepository pathRepository;
    private final ContentRepository contentRepository;

    public PathController(
            PathPlanner planner,
            GeneratePathUseCase generateUseCase,
            StartPathUseCase startUseCase,
            PausePathUseCase pauseUseCase,
            ResumePathUseCase resumeUseCase,
            AbandonPathUseCase abandonUseCase,
            MarkPathStepDoneUseCase markStepDoneUseCase,
            LearnerPathRepository pathRepository,
            ContentRepository contentRepository) {
        this.planner = planner;
        this.generateUseCase = generateUseCase;
        this.startUseCase = startUseCase;
        this.pauseUseCase = pauseUseCase;
        this.resumeUseCase = resumeUseCase;
        this.abandonUseCase = abandonUseCase;
        this.markStepDoneUseCase = markStepDoneUseCase;
        this.pathRepository = pathRepository;
        this.contentRepository = contentRepository;
    }

    @GetMapping
    public LearningPathResponse get(
            @RequestParam Long targetTopicId,
            @RequestParam(defaultValue = "false") boolean persist) {
        UserId userId = principalUserId();
        TopicId target = TopicId.of(targetTopicId);

        if (persist) {
            PathId id = generateUseCase.handle(userId, target);
            LearnerPath saved = pathRepository.findById(id).orElseThrow();
            return LearningPathResponse.from(saved, resolveTitles(saved.steps()));
        }
        // Preview path — planner only, no persistence.
        LearnerPathDraft draft = planner.plan(userId, target);
        return LearningPathResponse.preview(draft, resolveTitles(draft.steps()));
    }

    @PostMapping("/{pathId}/start")
    public ResponseEntity<Void> start(@PathVariable Long pathId) {
        startUseCase.handle(PathId.of(pathId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{pathId}/pause")
    public ResponseEntity<Void> pause(@PathVariable Long pathId) {
        pauseUseCase.handle(PathId.of(pathId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{pathId}/resume")
    public ResponseEntity<Void> resume(@PathVariable Long pathId) {
        resumeUseCase.handle(PathId.of(pathId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{pathId}/abandon")
    public ResponseEntity<Void> abandon(@PathVariable Long pathId) {
        abandonUseCase.handle(PathId.of(pathId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{pathId}/steps/{stepOrder}/done")
    public ResponseEntity<Void> markDone(
            @PathVariable Long pathId, @PathVariable int stepOrder) {
        markStepDoneUseCase.handle(PathId.of(pathId), stepOrder);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/active")
    public List<LearningPathSummary> activePaths() {
        UserId userId = principalUserId();
        List<LearnerPath> recent = pathRepository.findRecentByUser(userId, 10);
        return recent.stream()
                .filter(p -> p.status().isActive())
                .map(p -> LearningPathSummary.from(p, resolveTitles(p.steps())))
                .toList();
    }

    private UserId principalUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return UserId.of(UUID.fromString(auth.getName()));
    }

    private Map<ContentId, String> resolveTitles(
            List<com.plrs.domain.path.LearnerPathStep> steps) {
        Set<ContentId> ids = new HashSet<>();
        for (com.plrs.domain.path.LearnerPathStep s : steps) {
            ids.add(s.contentId());
        }
        Map<ContentId, String> titles = new HashMap<>();
        for (ContentId cid : ids) {
            Content c = contentRepository.findById(cid).orElse(null);
            titles.put(cid, c != null ? c.title() : "(unknown)");
        }
        return titles;
    }
}
