package com.plrs.infrastructure.recommendation;

import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.recommendation.RecommendationRepository;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserId;
import com.plrs.infrastructure.warehouse.DimensionResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Adapter implementing {@link RecommendationRepository} on top of
 * Spring Data JPA.
 *
 * <p>{@code saveAll} uses {@link EntityManager#persist} per row (not
 * {@code jpa.saveAll}) because Spring Data's save calls {@code merge}
 * for composite-PK entities — that would silently update an existing
 * row instead of raising the primary-key violation a re-served slate
 * with a clashing {@code created_at} should produce.
 *
 * <p>{@code recordClick} is a targeted native UPDATE rather than
 * load + save so concurrent clicks on different rows for the same user
 * don't collide on optimistic locking, and the WHERE-clause guard
 * preserves the aggregate's "earliest click wins" idempotency without
 * a read-modify-write round-trip.
 *
 * <p>Not declared {@code final}; gated by
 * {@code @ConditionalOnProperty("spring.datasource.url")}. Same pattern
 * as the other Spring Data adapters.
 *
 * <p>Traces to: §3.a, §3.c.1.4, FR-26/27/29.
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataRecommendationRepository implements RecommendationRepository {

    private static final Logger log =
            LoggerFactory.getLogger(SpringDataRecommendationRepository.class);

    private final RecommendationJpaRepository jpa;
    private final RecommendationMapper mapper;
    /**
     * Optional dependencies for the Iter 4 fact_recommendation backfill
     * (step 152). Resolved through {@link ObjectProvider} so slice ITs
     * that scan only this package don't have to wire ContentRepository
     * + TopicRepository + DimensionResolver + DataSource. When any of
     * the four is absent the dw mirror simply no-ops; the core ops
     * persistence path is unaffected.
     */
    private final ObjectProvider<ContentRepository> contentRepositoryProvider;

    private final ObjectProvider<TopicRepository> topicRepositoryProvider;
    private final ObjectProvider<DimensionResolver> dimensionsProvider;
    private final ObjectProvider<DataSource> dataSourceProvider;

    @PersistenceContext private EntityManager em;

    public SpringDataRecommendationRepository(
            RecommendationJpaRepository jpa,
            RecommendationMapper mapper,
            ObjectProvider<ContentRepository> contentRepositoryProvider,
            ObjectProvider<TopicRepository> topicRepositoryProvider,
            ObjectProvider<DimensionResolver> dimensionsProvider,
            ObjectProvider<DataSource> dataSourceProvider) {
        this.jpa = jpa;
        this.mapper = mapper;
        this.contentRepositoryProvider = contentRepositoryProvider;
        this.topicRepositoryProvider = topicRepositoryProvider;
        this.dimensionsProvider = dimensionsProvider;
        this.dataSourceProvider = dataSourceProvider;
    }

    @Override
    public void saveAll(List<Recommendation> recs) {
        for (Recommendation r : recs) {
            em.persist(mapper.toEntity(r));
        }
        // Mirror to plrs_dw.fact_recommendation in the same transaction
        // so the FR-36 KPI views see the slate without waiting for the
        // Kafka → ETL path. See V20 / Iter 4 deviation note. Skipped
        // when the optional dw beans aren't on the classpath.
        ContentRepository contentRepository = contentRepositoryProvider.getIfAvailable();
        DimensionResolver dimensions = dimensionsProvider.getIfAvailable();
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (contentRepository == null || dimensions == null || dataSource == null) {
            return;
        }
        TopicRepository topicRepository = topicRepositoryProvider.getIfAvailable();
        JdbcTemplate dwJdbc = new JdbcTemplate(dataSource);
        for (Recommendation r : recs) {
            try {
                writeFactRow(r, contentRepository, topicRepository, dimensions, dwJdbc);
            } catch (Exception e) {
                // Best-effort: a fact-row failure must not undo the ops
                // write. The KPI MVs simply won't see this slate.
                log.warn(
                        "Failed to write fact_recommendation for user={} content={}",
                        r.userId().value(),
                        r.contentId().value(),
                        e);
            }
        }
    }

    private void writeFactRow(
            Recommendation r,
            ContentRepository contentRepository,
            TopicRepository topicRepository,
            DimensionResolver dimensions,
            JdbcTemplate dwJdbc) {
        Content content = contentRepository.findById(r.contentId()).orElse(null);
        if (content == null) {
            return;
        }
        TopicId topicId = content.topicId();
        Topic topic =
                topicRepository != null ? topicRepository.findById(topicId).orElse(null) : null;
        Long userSk = dimensions.ensureUserSk(r.userId());
        Long contentSk =
                dimensions.ensureContentSk(content.id(), topicId.value(), content.title());
        Long topicSk =
                dimensions.ensureTopicSk(topicId, topic != null ? topic.name() : null);
        int dateSk = dimensions.dateSk(r.createdAt());

        dwJdbc.update(
                "INSERT INTO plrs_dw.fact_recommendation"
                        + " (date_sk, user_sk, content_sk, topic_sk, created_at,"
                        + "  score, rank_position, variant_name, was_clicked, was_completed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, FALSE)"
                        + " ON CONFLICT (date_sk, user_sk, content_sk, created_at) DO NOTHING",
                dateSk,
                userSk,
                contentSk,
                topicSk,
                Timestamp.from(r.createdAt()),
                r.score().toBigDecimal(),
                (short) r.rankPosition(),
                r.modelVariant());
    }

    @Override
    public Optional<Recommendation> find(
            UserId userId, ContentId contentId, Instant createdAt) {
        return jpa.findById(new RecommendationKey(userId.value(), contentId.value(), createdAt))
                .map(mapper::toDomain);
    }

    @Override
    public void recordClick(
            UserId userId, ContentId contentId, Instant createdAt, Instant clickedAt) {
        // The WHERE-clause guard ensures we only stamp the click on
        // rows that haven't been clicked yet — earliest click wins,
        // matching Recommendation.recordClick's idempotency.
        em.createNativeQuery(
                        "UPDATE plrs_ops.recommendations"
                                + " SET clicked_at = :clickedAt"
                                + " WHERE user_id = :userId"
                                + "   AND content_id = :contentId"
                                + "   AND created_at = :createdAt"
                                + "   AND clicked_at IS NULL")
                .setParameter("clickedAt", clickedAt)
                .setParameter("userId", userId.value())
                .setParameter("contentId", contentId.value())
                .setParameter("createdAt", createdAt)
                .executeUpdate();

        // Mirror was_clicked to fact_recommendation so the FR-36 CTR MV
        // sees the click. Best-effort; no-ops when the optional dw beans
        // aren't on the slice-test classpath.
        ContentRepository contentRepository = contentRepositoryProvider.getIfAvailable();
        DimensionResolver dimensions = dimensionsProvider.getIfAvailable();
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (contentRepository == null || dimensions == null || dataSource == null) {
            return;
        }
        try {
            Long userSk = dimensions.ensureUserSk(userId);
            Content content = contentRepository.findById(contentId).orElse(null);
            if (content != null) {
                Long contentSk =
                        dimensions.ensureContentSk(
                                contentId, content.topicId().value(), content.title());
                int dateSk = dimensions.dateSk(createdAt);
                new JdbcTemplate(dataSource)
                        .update(
                                "UPDATE plrs_dw.fact_recommendation"
                                        + " SET was_clicked = TRUE"
                                        + " WHERE date_sk = ? AND user_sk = ?"
                                        + "   AND content_sk = ? AND created_at = ?",
                                dateSk,
                                userSk,
                                contentSk,
                                Timestamp.from(createdAt));
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to mirror click to fact_recommendation user={} content={}",
                    userId.value(),
                    contentId.value(),
                    e);
        }
    }

    @Override
    public List<Recommendation> findRecent(UserId userId, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0, got " + limit);
        }
        if (limit == 0) {
            return List.of();
        }
        return jpa.findByUserIdOrderByCreatedAtDesc(userId.value(), PageRequest.of(0, limit))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
