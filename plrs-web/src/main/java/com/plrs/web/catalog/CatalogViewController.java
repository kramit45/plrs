package com.plrs.web.catalog;

import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.SearchPage;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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

    public CatalogViewController(
            ContentRepository contentRepository, TopicRepository topicRepository) {
        this.contentRepository = contentRepository;
        this.topicRepository = topicRepository;
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
}
