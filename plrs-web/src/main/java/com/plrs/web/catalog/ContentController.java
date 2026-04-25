package com.plrs.web.catalog;

import com.plrs.application.content.AddPrerequisiteCommand;
import com.plrs.application.content.AddPrerequisiteUseCase;
import com.plrs.application.content.ContentNotFoundException;
import com.plrs.application.content.CreateContentCommand;
import com.plrs.application.content.CreateContentUseCase;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for the content catalogue and its prerequisite DAG.
 *
 * <p>{@code POST /api/content} (INSTRUCTOR / ADMIN) — create a non-quiz
 * content item; 201 with {@code Location} on success. {@link ContentType#QUIZ}
 * routes through {@code AuthorQuizUseCase} (step 81) and produces a 400
 * here. Other failure shapes: 404 unknown topic, 409 duplicate
 * {@code (topic, title)}, 400 validation breach.
 *
 * <p>{@code GET /api/content/{id}} — load a single content item with its
 * direct prerequisite and dependent edges populated; 404 when absent.
 *
 * <p>{@code POST /api/content/{id}/prerequisites} (INSTRUCTOR / ADMIN) —
 * add an edge "{@code id} requires {@code prereqContentId}"; 201 always
 * (idempotent — adding an existing edge is a no-op but the resource is
 * present post-operation). 409 with {@code cyclePath} on cycle. 404 if
 * either content is unknown.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 *
 * <p>Traces to: FR-08 (content authoring), FR-09 (prerequisite tracking).
 */
@RestController
@RequestMapping("/api/content")
@ConditionalOnProperty(name = "spring.datasource.url")
public class ContentController {

    private final CreateContentUseCase createContentUseCase;
    private final AddPrerequisiteUseCase addPrereqUseCase;
    private final ContentRepository contentRepository;
    private final PrerequisiteRepository prereqRepository;

    public ContentController(
            CreateContentUseCase createContentUseCase,
            AddPrerequisiteUseCase addPrereqUseCase,
            ContentRepository contentRepository,
            PrerequisiteRepository prereqRepository) {
        this.createContentUseCase = createContentUseCase;
        this.addPrereqUseCase = addPrereqUseCase;
        this.contentRepository = contentRepository;
        this.prereqRepository = prereqRepository;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public ResponseEntity<ContentCreatedResponse> create(
            @Valid @RequestBody CreateContentRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID uuid = UUID.fromString(auth.getName());
        ContentId id =
                createContentUseCase.handle(
                        new CreateContentCommand(
                                TopicId.of(req.topicId()),
                                req.title(),
                                ContentType.fromName(req.ctype()),
                                Difficulty.fromName(req.difficulty()),
                                req.estMinutes(),
                                req.url(),
                                Optional.ofNullable(req.description()),
                                Set.copyOf(req.tags() == null ? Set.of() : req.tags()),
                                Optional.of(UserId.of(uuid)),
                                auth.getName()));
        URI loc = URI.create("/api/content/" + id.value());
        return ResponseEntity.created(loc)
                .body(new ContentCreatedResponse(id.value(), req.title()));
    }

    @GetMapping("/{id}")
    public ContentDetailResponse get(@PathVariable Long id) {
        Content c =
                contentRepository
                        .findById(ContentId.of(id))
                        .orElseThrow(() -> new ContentNotFoundException(ContentId.of(id)));
        List<PrerequisiteEdge> prereqs = prereqRepository.findDirectPrerequisitesOf(c.id());
        List<PrerequisiteEdge> dependents = prereqRepository.findDirectDependentsOf(c.id());
        return ContentDetailResponse.from(c, prereqs, dependents);
    }

    @PostMapping("/{id}/prerequisites")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public ResponseEntity<Void> addPrereq(
            @PathVariable Long id, @Valid @RequestBody AddPrereqRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID uuid = UUID.fromString(auth.getName());
        addPrereqUseCase.handle(
                new AddPrerequisiteCommand(
                        ContentId.of(id),
                        ContentId.of(req.prereqContentId()),
                        Optional.of(UserId.of(uuid))));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
