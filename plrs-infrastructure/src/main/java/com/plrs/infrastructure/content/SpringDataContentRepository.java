package com.plrs.infrastructure.content;

import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentDraft;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.SearchPage;
import com.plrs.domain.topic.TopicId;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Adapter implementing the domain-owned
 * {@link com.plrs.domain.content.ContentRepository} port on top of Spring
 * Data JPA. Delegates CRUD to {@link ContentJpaRepository} and converts
 * between the domain aggregate and the JPA entity via
 * {@link ContentMapper}.
 *
 * <p>{@link #search(String, int, int)} backs FR-13 keyword search with
 * two native queries (data + count) over Postgres's {@code tsvector}
 * operators. The V5 migration's GIN index {@code idx_content_search}
 * accelerates the {@code @@} predicate; {@code ts_rank} drives the
 * relevance ordering with {@code created_at DESC} as a tiebreaker.
 *
 * <p>Not declared {@code final}: Spring Boot's observation / metrics
 * {@code AbstractAdvisingBeanPostProcessor} tries to CGLIB-subclass every
 * {@code @Component} bean and blows up on a final class. Same constraint
 * as {@code SpringDataUserRepository} and {@code SpringDataTopicRepository}.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test. Matches the gate on sibling adapters.
 *
 * <p>Pagination bounds ({@code pageSize in [1, 100]}, {@code pageNumber
 * >= 0}) are enforced here via {@link IllegalArgumentException}; the use
 * case layer wraps these into a domain-level outcome before the request
 * escapes the service boundary. Blank queries short-circuit to an empty
 * page without issuing SQL.
 *
 * <p>Traces to: §3.a (adapter implements domain port), §3.c.1.3 (content
 * persistence and GIN index), FR-13 (paginated keyword search).
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataContentRepository implements ContentRepository {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final ContentJpaRepository jpa;
    private final ContentMapper mapper;

    public SpringDataContentRepository(ContentJpaRepository jpa, ContentMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public Content save(ContentDraft draft) {
        ContentJpaEntity saved = jpa.save(mapper.toEntity(draft));
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Content> findById(ContentId id) {
        return jpa.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public List<Content> findByTopicId(TopicId topicId) {
        return jpa.findByTopicId(topicId.value()).stream().map(mapper::toDomain).toList();
    }

    @Override
    public boolean existsByTopicIdAndTitle(TopicId topicId, String title) {
        return jpa.existsByTopicIdAndTitle(topicId.value(), title);
    }

    @Override
    public SearchPage search(String query, int pageSize, int pageNumber) {
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "pageSize must be in [" + MIN_PAGE_SIZE + ", " + MAX_PAGE_SIZE
                            + "], got " + pageSize);
        }
        if (pageNumber < 0) {
            throw new IllegalArgumentException(
                    "pageNumber must be >= 0, got " + pageNumber);
        }
        if (query == null || query.isBlank()) {
            return new SearchPage(List.of(), pageNumber, pageSize, 0L, 0);
        }
        int offset = pageNumber * pageSize;
        List<Content> items =
                jpa.searchByTsQuery(query, pageSize, offset).stream()
                        .map(mapper::toDomain)
                        .toList();
        long total = jpa.countByTsQuery(query);
        int totalPages = (int) Math.ceil((double) total / pageSize);
        return new SearchPage(items, pageNumber, pageSize, total, totalPages);
    }
}
