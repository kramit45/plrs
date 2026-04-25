package com.plrs.infrastructure.content;

import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentDraft;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.SearchPage;
import com.plrs.domain.quiz.QuizItem;
import com.plrs.domain.quiz.QuizItemOption;
import com.plrs.domain.topic.TopicId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @PersistenceContext private EntityManager em;

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
        return jpa.findById(id.value()).map(this::hydrate);
    }

    /**
     * For ctype=QUIZ content, fetches quiz_items + quiz_item_options via
     * a single native join and rehydrates the aggregate through the
     * 12-arg {@link Content#rehydrate} variant. For non-QUIZ content,
     * delegates to {@link ContentMapper#toDomain(ContentJpaEntity)}.
     */
    private Content hydrate(ContentJpaEntity entity) {
        if (entity.getCtype() != ContentType.QUIZ) {
            return mapper.toDomain(entity);
        }
        return mapper.toDomain(entity, loadQuizItems(entity.getId()));
    }

    @SuppressWarnings("unchecked")
    private List<QuizItem> loadQuizItems(Long contentId) {
        List<Object[]> rows =
                em.createNativeQuery(
                                "SELECT qi.item_order, qi.topic_id, qi.stem, qi.explanation,"
                                        + "       qo.option_order, qo.option_text, qo.is_correct"
                                        + " FROM plrs_ops.quiz_items qi"
                                        + " JOIN plrs_ops.quiz_item_options qo"
                                        + "   ON qo.content_id = qi.content_id"
                                        + "  AND qo.item_order = qi.item_order"
                                        + " WHERE qi.content_id = :contentId"
                                        + " ORDER BY qi.item_order ASC, qo.option_order ASC")
                        .setParameter("contentId", contentId)
                        .getResultList();

        // Group rows by itemOrder, preserving insertion order so the
        // resulting List<QuizItem> matches item_order ASC.
        Map<Integer, ItemBuilder> byItemOrder = new LinkedHashMap<>();
        for (Object[] row : rows) {
            int itemOrder = ((Number) row[0]).intValue();
            ItemBuilder b =
                    byItemOrder.computeIfAbsent(
                            itemOrder,
                            order -> {
                                Long topicId = ((Number) row[1]).longValue();
                                String stem = (String) row[2];
                                String explanation = (String) row[3];
                                return new ItemBuilder(order, topicId, stem, explanation);
                            });
            b.options.add(
                    new QuizItemOption(
                            ((Number) row[4]).intValue(),
                            (String) row[5],
                            (Boolean) row[6]));
        }
        List<QuizItem> items = new ArrayList<>(byItemOrder.size());
        for (ItemBuilder b : byItemOrder.values()) {
            items.add(b.build());
        }
        return items;
    }

    private static final class ItemBuilder {
        final int itemOrder;
        final Long topicId;
        final String stem;
        final String explanation;
        final List<QuizItemOption> options = new ArrayList<>();

        ItemBuilder(int itemOrder, Long topicId, String stem, String explanation) {
            this.itemOrder = itemOrder;
            this.topicId = topicId;
            this.stem = stem;
            this.explanation = explanation;
        }

        QuizItem build() {
            return QuizItem.of(
                    itemOrder,
                    com.plrs.domain.topic.TopicId.of(topicId),
                    stem,
                    Optional.ofNullable(explanation),
                    options);
        }
    }

    @Override
    public List<Content> findByTopicId(TopicId topicId) {
        return jpa.findByTopicId(topicId.value()).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Content> findAllNonQuiz(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0, got " + limit);
        }
        if (limit == 0) {
            return List.of();
        }
        // Non-QUIZ rows by definition carry no quiz items, so the
        // single-arg toDomain is safe here — no need to hit the
        // quiz-items native join.
        return jpa.findAllNonQuiz(limit).stream().map(mapper::toDomain).toList();
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

    @Override
    public Content saveQuiz(com.plrs.domain.content.QuizContentDraft draft) {
        // Stub: real implementation lands in step 81 alongside the
        // AuthorQuizUseCase. Throws so accidental Iter-2 calls fail loudly
        // rather than silently no-oping.
        throw new UnsupportedOperationException(
                "saveQuiz not yet implemented; lands in step 81");
    }
}
