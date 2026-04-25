package com.plrs.infrastructure.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentDraft;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.content.SearchPage;
import com.plrs.domain.topic.TopicId;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for the {@link SpringDataContentRepository} adapter.
 * Drives the {@link ContentRepository} port to prove Spring wires
 * port→adapter correctly, and exercises the FR-13 tsvector search end to
 * end with seeded fixtures.
 *
 * <p>Explicitly flushes via {@link EntityManager#flush()} after seeding
 * because Hibernate's auto-flush is not guaranteed to fire for native
 * queries; without the flush, the tsvector search would miss rows that
 * exist only in the pending-insert batch.
 *
 * <p>One test confirms that Postgres can reach {@code idx_content_search}
 * for the search query shape by running {@code EXPLAIN} with
 * {@code enable_seqscan = off}; the plan must mention the index name.
 * This satisfies the step-59 acceptance criterion without requiring a
 * large seed to coax the planner away from a sequential scan.
 *
 * <p>Traces to: §3.a (adapter implements domain port), §3.c.1.3 (content
 * persistence + GIN index), FR-13 (paginated keyword search).
 */
@SpringBootTest(
        classes = SpringDataContentRepositoryIT.ContentRepoITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=plrs_ops"
        })
@Transactional
class SpringDataContentRepositoryIT extends PostgresTestBase {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);

    @Autowired private ContentRepository contentRepository;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private TopicId seedTopic() {
        try (var conn = dataSource.getConnection();
                var ps =
                        conn.prepareStatement(
                                "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                                        + " VALUES (?, 'test') RETURNING topic_id")) {
            ps.setString(1, unique("topic"));
            try (var rs = ps.executeQuery()) {
                rs.next();
                return TopicId.of(rs.getLong("topic_id"));
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Content seedContent(TopicId topicId, String title, String description) {
        ContentDraft draft =
                new ContentDraft(
                        topicId,
                        title,
                        ContentType.ARTICLE,
                        Difficulty.BEGINNER,
                        10,
                        "https://example.com/" + UUID.randomUUID(),
                        description == null ? Optional.empty() : Optional.of(description),
                        Set.of(),
                        Optional.empty(),
                        AuditFields.initial("system", CLOCK));
        return contentRepository.save(draft);
    }

    private List<Content> seedGraphFixtures(TopicId topicId) {
        List<Content> items =
                List.of(
                        seedContent(topicId, "Intro to Algorithms", "Foundations of algorithmic thinking"),
                        seedContent(topicId, "Advanced Graph Theory", "Deep dive into graph structures"),
                        seedContent(topicId, "Dynamic Programming", "Memoization and tabulation"),
                        seedContent(
                                topicId,
                                "Graph Search: BFS and DFS",
                                "Breadth-first and depth-first graph traversal"));
        em.flush();
        return items;
    }

    @Test
    void saveDraftReturnsContentWithAssignedId() {
        TopicId topicId = seedTopic();
        ContentDraft draft =
                new ContentDraft(
                        topicId,
                        unique("title"),
                        ContentType.VIDEO,
                        Difficulty.BEGINNER,
                        10,
                        "https://example.com/v",
                        Optional.empty(),
                        Set.of("x"),
                        Optional.empty(),
                        AuditFields.initial("system", CLOCK));

        Content saved = contentRepository.save(draft);

        assertThat(saved.id()).isNotNull();
        assertThat(saved.id().value()).isPositive();
        assertThat(saved.topicId()).isEqualTo(topicId);
        assertThat(saved.tags()).containsExactly("x");
    }

    @Test
    void findByTopicIdReturnsOnlyMatchingTopic() {
        TopicId topicA = seedTopic();
        TopicId topicB = seedTopic();
        Content a = seedContent(topicA, unique("a"), null);
        Content b = seedContent(topicA, unique("a2"), null);
        seedContent(topicB, unique("b"), null);
        em.flush();

        List<Content> list = contentRepository.findByTopicId(topicA);

        assertThat(list).extracting(Content::id).containsExactlyInAnyOrder(a.id(), b.id());
    }

    @Test
    void existsByTopicIdAndTitleHonoursUniqueConstraint() {
        TopicId topicId = seedTopic();
        String title = unique("t");
        seedContent(topicId, title, null);
        em.flush();

        assertThat(contentRepository.existsByTopicIdAndTitle(topicId, title)).isTrue();
        assertThat(contentRepository.existsByTopicIdAndTitle(topicId, unique("other"))).isFalse();
    }

    @Test
    void searchReturnsItemsMatchingKeyword() {
        TopicId topicId = seedTopic();
        seedGraphFixtures(topicId);

        SearchPage page = contentRepository.search("graph", 10, 0);

        assertThat(page.items())
                .extracting(Content::title)
                .anyMatch(t -> t.contains("Graph"))
                .filteredOn(t -> t.contains("Graph"))
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(page.totalElements()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void searchOrdersByRelevanceThenCreatedAtDesc() {
        TopicId topicId = seedTopic();
        seedGraphFixtures(topicId);

        SearchPage page = contentRepository.search("graph traversal", 10, 0);

        assertThat(page.items()).isNotEmpty();
        // The item whose title+description both mention graph+traversal ("Graph Search: BFS and DFS")
        // should outrank one that only mentions graph ("Advanced Graph Theory").
        assertThat(page.items().get(0).title()).contains("Graph Search");
    }

    @Test
    void searchPaginatesDeterministically() {
        TopicId topicId = seedTopic();
        seedGraphFixtures(topicId);

        SearchPage page0 = contentRepository.search("graph", 1, 0);
        SearchPage page1 = contentRepository.search("graph", 1, 1);

        assertThat(page0.items()).hasSize(1);
        assertThat(page1.items()).hasSize(1);
        assertThat(page0.pageNumber()).isZero();
        assertThat(page1.pageNumber()).isEqualTo(1);
        // Pages must not overlap.
        assertThat(page0.items().get(0).id()).isNotEqualTo(page1.items().get(0).id());
    }

    @Test
    void searchReturnsEmptyPageForUnknownTerm() {
        TopicId topicId = seedTopic();
        seedGraphFixtures(topicId);

        SearchPage page = contentRepository.search("zzzz-nonsense-" + UUID.randomUUID(), 10, 0);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    void searchWithBlankQueryShortCircuitsToEmptyPage() {
        SearchPage page = contentRepository.search("   ", 10, 0);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.pageSize()).isEqualTo(10);
    }

    @Test
    void searchRejectsInvalidPageSize() {
        // Spring's @Repository exception translation wraps IllegalArgumentException
        // into InvalidDataAccessApiUsageException; assert on message content instead.
        assertThatThrownBy(() -> contentRepository.search("q", 0, 0))
                .hasMessageContaining("pageSize");
        assertThatThrownBy(() -> contentRepository.search("q", 101, 0))
                .hasMessageContaining("pageSize");
    }

    @Test
    void searchRejectsNegativePageNumber() {
        assertThatThrownBy(() -> contentRepository.search("q", 10, -1))
                .hasMessageContaining("pageNumber");
    }

    @Test
    void explainShowsPlannerCanReachGinIndex() throws Exception {
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute("SET enable_seqscan = off");
            try (var rs = stmt.executeQuery(
                    "EXPLAIN SELECT c.* FROM plrs_ops.content c"
                            + " WHERE to_tsvector('english',"
                            + "                    c.title || ' ' || coalesce(c.description, ''))"
                            + "       @@ plainto_tsquery('english', 'graph')")) {
                StringBuilder plan = new StringBuilder();
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
                assertThat(plan.toString())
                        .as(
                                "planner must be able to use idx_content_search when seqscan is"
                                        + " disabled")
                        .contains("idx_content_search");
            }
        }
    }

    @SpringBootApplication(
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class ContentRepoITApp {}
}
