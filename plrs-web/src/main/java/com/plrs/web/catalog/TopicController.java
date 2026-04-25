package com.plrs.web.catalog;

import com.plrs.application.topic.CreateTopicCommand;
import com.plrs.application.topic.CreateTopicUseCase;
import com.plrs.application.topic.TopicNotFoundException;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for the topic catalogue.
 *
 * <p>{@code POST /api/topics} (INSTRUCTOR / ADMIN) — create a topic;
 * 201 with {@code Location} on success, 409 on duplicate name, 404 on
 * unknown parent, 400 on validation breach.
 *
 * <p>{@code GET /api/topics/{id}} — load a single topic; 404 when
 * absent. Open to any authenticated user (read access is the default
 * for browsing the catalogue).
 *
 * <p>{@code GET /api/topics?parentId=N} — list children; without
 * {@code parentId}, returns the root topics.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 *
 * <p>Traces to: FR-07 (topic hierarchy authoring + browsing).
 */
@RestController
@RequestMapping("/api/topics")
@ConditionalOnProperty(name = "spring.datasource.url")
public class TopicController {

    private final CreateTopicUseCase createTopicUseCase;
    private final TopicRepository topicRepository;

    public TopicController(CreateTopicUseCase createTopicUseCase, TopicRepository topicRepository) {
        this.createTopicUseCase = createTopicUseCase;
        this.topicRepository = topicRepository;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public ResponseEntity<TopicCreatedResponse> create(
            @Valid @RequestBody CreateTopicRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        TopicId id =
                createTopicUseCase.handle(
                        new CreateTopicCommand(
                                req.name(),
                                req.description(),
                                Optional.ofNullable(req.parentTopicId()).map(TopicId::of),
                                auth.getName()));
        URI location = URI.create("/api/topics/" + id.value());
        return ResponseEntity.created(location)
                .body(new TopicCreatedResponse(id.value(), req.name()));
    }

    @GetMapping("/{id}")
    public TopicResponse get(@PathVariable Long id) {
        Topic t =
                topicRepository
                        .findById(TopicId.of(id))
                        .orElseThrow(() -> new TopicNotFoundException(TopicId.of(id)));
        return TopicResponse.from(t);
    }

    @GetMapping
    public List<TopicResponse> list(@RequestParam(required = false) Long parentId) {
        List<Topic> topics =
                parentId == null
                        ? topicRepository.findRootTopics()
                        : topicRepository.findChildrenOf(TopicId.of(parentId));
        return topics.stream().map(TopicResponse::from).toList();
    }
}
