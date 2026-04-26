package com.plrs.web.path;

import com.plrs.application.path.GeneratePathUseCase;
import com.plrs.application.path.MarkPathStepDoneUseCase;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathRepository;
import com.plrs.domain.path.PathId;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Server-rendered learner-path UI under the session-cookie web chain.
 *
 * <p>Three Thymeleaf entry points:
 *
 * <ul>
 *   <li>{@code GET /path/generate} — form to pick a target topic;
 *   <li>{@code POST /path/generate} — kicks off
 *       {@link GeneratePathUseCase} (TX-10), redirects to the new
 *       {@code /path/{id}} on success;
 *   <li>{@code GET /path/{id}} — renders the step list with status
 *       badges and per-step Mark-done buttons;
 *   <li>{@code POST /path/{id}/steps/{stepOrder}/done} — handles the
 *       per-step button click via {@link MarkPathStepDoneUseCase} and
 *       redirects back to the view.
 * </ul>
 *
 * <p>Traces to: FR-31..FR-34, FR-35.
 */
@Controller
@ConditionalOnProperty(name = "spring.datasource.url")
public class PathViewController {

    private final GeneratePathUseCase generateUseCase;
    private final MarkPathStepDoneUseCase markStepDoneUseCase;
    private final LearnerPathRepository pathRepository;
    private final ContentRepository contentRepository;
    private final TopicRepository topicRepository;

    public PathViewController(
            GeneratePathUseCase generateUseCase,
            MarkPathStepDoneUseCase markStepDoneUseCase,
            LearnerPathRepository pathRepository,
            ContentRepository contentRepository,
            TopicRepository topicRepository) {
        this.generateUseCase = generateUseCase;
        this.markStepDoneUseCase = markStepDoneUseCase;
        this.pathRepository = pathRepository;
        this.contentRepository = contentRepository;
        this.topicRepository = topicRepository;
    }

    @GetMapping("/path/generate")
    @PreAuthorize("hasRole('STUDENT')")
    public String generateForm(Model model) {
        // Show every topic so the student can pick a target. Lightweight
        // — the catalogue is small in the demo.
        List<Topic> topics = topicRepository.findRootTopics();
        model.addAttribute("topics", topics);
        return "path/generate";
    }

    @PostMapping("/path/generate")
    @PreAuthorize("hasRole('STUDENT')")
    public String generate(@RequestParam Long targetTopicId) {
        UserId userId = principalUserId();
        PathId id = generateUseCase.handle(userId, TopicId.of(targetTopicId));
        return "redirect:/path/" + id.value();
    }

    @GetMapping("/path/{id}")
    @PreAuthorize("hasRole('STUDENT')")
    public String view(@PathVariable Long id, Model model) {
        LearnerPath path = pathRepository.findById(PathId.of(id)).orElseThrow();
        Map<ContentId, String> titles = resolveTitles(path);
        model.addAttribute("path", LearningPathResponse.from(path, titles));
        // The target topic name on the header.
        model.addAttribute(
                "targetTopicName",
                topicRepository.findById(path.targetTopicId()).map(Topic::name).orElse("(unknown)"));
        return "path/view";
    }

    @PostMapping("/path/{id}/steps/{stepOrder}/done")
    @PreAuthorize("hasRole('STUDENT')")
    public String markStepDone(@PathVariable Long id, @PathVariable int stepOrder) {
        markStepDoneUseCase.handle(PathId.of(id), stepOrder);
        return "redirect:/path/" + id;
    }

    private UserId principalUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return UserId.of(UUID.fromString(auth.getName()));
    }

    private Map<ContentId, String> resolveTitles(LearnerPath path) {
        Set<ContentId> ids = new HashSet<>();
        path.steps().forEach(s -> ids.add(s.contentId()));
        Map<ContentId, String> titles = new HashMap<>();
        for (ContentId cid : ids) {
            Content c = contentRepository.findById(cid).orElse(null);
            titles.put(cid, c != null ? c.title() : "(unknown)");
        }
        return titles;
    }
}
