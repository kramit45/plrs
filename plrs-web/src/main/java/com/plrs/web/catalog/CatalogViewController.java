package com.plrs.web.catalog;

import com.plrs.application.content.ContentNotFoundException;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.content.SearchPage;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Server-rendered catalogue browse view at {@code GET /catalog}. Reuses
 * the same {@code ContentRepository.search} surface as the JSON
 * {@link ContentController#search} endpoint, so both client paths share
 * the same FR-13 GIN-backed query.
 *
 * <p>Pagination is clamped (not rejected) — the view never 400s on
 * pagination inputs. Empty results render two distinct states:
 *
 * <ul>
 *   <li>{@code q} blank → "Showing root topics" with the
 *       {@link TopicRepository#findRootTopics()} list, since blank queries
 *       short-circuit to an empty {@link SearchPage} per step 59.
 *   <li>{@code q} non-blank but no matches → "No results for 'q'" empty
 *       state.
 * </ul>
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 *
 * <p>Traces to: FR-13 (paginated keyword search), EIR-01 (Thymeleaf +
 * Bootstrap browse view).
 */
@Controller
@ConditionalOnProperty(name = "spring.datasource.url")
public class CatalogViewController {

    private final ContentRepository contentRepository;
    private final TopicRepository topicRepository;
    private final PrerequisiteRepository prereqRepository;

    public CatalogViewController(
            ContentRepository contentRepository,
            TopicRepository topicRepository,
            PrerequisiteRepository prereqRepository) {
        this.contentRepository = contentRepository;
        this.topicRepository = topicRepository;
        this.prereqRepository = prereqRepository;
    }

    @GetMapping("/catalog")
    public String browse(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "0") int pageNumber,
            Model model) {
        int ps = Math.max(1, Math.min(pageSize, 100));
        int pn = Math.max(0, pageNumber);
        SearchPage page = contentRepository.search(q.trim(), ps, pn);
        List<Topic> roots = topicRepository.findRootTopics();
        // Expose the items as ContentSummary DTOs (with JavaBean-friendly
        // record accessors) so the Thymeleaf template can read fields via
        // ${item.contentId} etc. instead of fighting domain accessors.
        List<ContentSummary> items =
                page.items().stream().map(ContentSummary::from).toList();
        model.addAttribute("q", q);
        model.addAttribute("page", page);
        model.addAttribute("items", items);
        model.addAttribute("pageSize", ps);
        model.addAttribute("pageNumber", pn);
        model.addAttribute("rootTopics", roots);
        return "catalog/browse";
    }

    /**
     * Detail view for a single content item. Renders metadata, the direct
     * prerequisite list (with titles batch-resolved), the direct dependents,
     * and a STUDENT-only placeholder for interaction history (data wiring
     * lands in step 72). Throws {@link ContentNotFoundException} on unknown
     * id; the global advice maps to 404.
     *
     * <p>Traces to: FR-14 (content detail view).
     */
    @GetMapping("/catalog/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Content c =
                contentRepository
                        .findById(ContentId.of(id))
                        .orElseThrow(() -> new ContentNotFoundException(ContentId.of(id)));
        Topic topic = topicRepository.findById(c.topicId()).orElse(null);
        List<PrerequisiteEdge> prereqs = prereqRepository.findDirectPrerequisitesOf(c.id());
        List<PrerequisiteEdge> dependents = prereqRepository.findDirectDependentsOf(c.id());

        Map<ContentId, String> titlesById = new HashMap<>();
        Stream.concat(
                        prereqs.stream().map(PrerequisiteEdge::prereqContentId),
                        dependents.stream().map(PrerequisiteEdge::contentId))
                .distinct()
                .forEach(
                        pid ->
                                contentRepository
                                        .findById(pid)
                                        .ifPresent(pc -> titlesById.put(pid, pc.title())));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isStudent =
                auth != null
                        && auth.getAuthorities().stream()
                                .anyMatch(a -> "ROLE_STUDENT".equals(a.getAuthority()));

        // Model attribute name "item" (not "content") because Thymeleaf
        // reserves "content" for the layout fragment dispatch in
        // ~{layout :: layout(~{::content})}.
        model.addAttribute("item", c);
        model.addAttribute("topic", topic);
        model.addAttribute("prereqs", prereqs);
        model.addAttribute("dependents", dependents);
        model.addAttribute("titlesById", titlesById);
        model.addAttribute("isStudent", isStudent);
        return "catalog/detail";
    }
}
