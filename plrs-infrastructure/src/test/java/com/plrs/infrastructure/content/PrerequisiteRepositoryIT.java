package com.plrs.infrastructure.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link SpringDataPrerequisiteRepository}. Drives
 * the {@link PrerequisiteRepository} port to confirm Spring wires the
 * adapter, and exercises the recursive-CTE cycle walk against a seeded
 * 6-node graph.
 *
 * <p>Edge semantics: an edge {@code (a, b)} reads "{@code a} requires
 * {@code b}" — {@code a} is the dependent, {@code b} is the prereq. A
 * cycle would form on adding {@code (content, prereq)} iff there is
 * already a "requires" path from {@code prereq} back to {@code content}.
 *
 * <p>Topics + content rows are seeded via raw SQL so this IT does not
 * need to broaden its scan beyond the {@code content} package
 * (broadening would pull in JWT auto-config that needs key-pair
 * properties this test cannot provide).
 *
 * <p>Traces to: §3.a (adapter), §3.c.1.3 (prerequisites), §2.e.2.5
 * (cycle detection), FR-09.
 */
@SpringBootTest(
        classes = PrerequisiteRepositoryIT.PrereqRepoITApp.class,
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
class PrerequisiteRepositoryIT extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    @Autowired private PrerequisiteRepository prereqRepo;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    private final Map<String, ContentId> nodes = new HashMap<>();

    @BeforeEach
    void seedSixContentRows() throws SQLException {
        nodes.clear();
        try (Connection conn = dataSource.getConnection()) {
            long topicId = insertTopic(conn);
            for (String label : List.of("c1", "c2", "c3", "c4", "c5", "c6")) {
                long id = insertContent(conn, topicId, label + "-" + UUID.randomUUID());
                nodes.put(label, ContentId.of(id));
            }
        }
        em.flush();
    }

    private static long insertTopic(Connection conn) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                        + " VALUES (?, 'test') RETURNING topic_id")) {
            ps.setString(1, "prereq-topic-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("topic_id");
            }
        }
    }

    private static long insertContent(Connection conn, long topicId, String title)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.content"
                        + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                        + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 10, 'https://x.y')"
                        + " RETURNING content_id")) {
            ps.setLong(1, topicId);
            ps.setString(2, title);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("content_id");
            }
        }
    }

    private PrerequisiteEdge saveEdge(String from, String to) {
        return prereqRepo.save(
                new PrerequisiteEdge(nodes.get(from), nodes.get(to), T0, Optional.empty()));
    }

    @Test
    void saveAndFindDirectPrerequisites() {
        saveEdge("c1", "c2");
        saveEdge("c1", "c3");
        em.flush();

        List<PrerequisiteEdge> edges = prereqRepo.findDirectPrerequisitesOf(nodes.get("c1"));

        assertThat(edges)
                .extracting(PrerequisiteEdge::prereqContentId)
                .containsExactlyInAnyOrder(nodes.get("c2"), nodes.get("c3"));
    }

    @Test
    void existsReflectsStorageState() {
        assertThat(prereqRepo.exists(nodes.get("c1"), nodes.get("c2"))).isFalse();

        saveEdge("c1", "c2");
        em.flush();

        assertThat(prereqRepo.exists(nodes.get("c1"), nodes.get("c2"))).isTrue();
        assertThat(prereqRepo.exists(nodes.get("c2"), nodes.get("c1"))).isFalse();
    }

    @Test
    void removeDeletesEdge() {
        saveEdge("c1", "c2");
        em.flush();
        assertThat(prereqRepo.exists(nodes.get("c1"), nodes.get("c2"))).isTrue();

        prereqRepo.remove(nodes.get("c1"), nodes.get("c2"));
        em.flush();

        assertThat(prereqRepo.exists(nodes.get("c1"), nodes.get("c2"))).isFalse();
    }

    @Test
    void removeOfNonExistentEdgeIsNoop() {
        prereqRepo.remove(nodes.get("c1"), nodes.get("c2"));

        assertThat(prereqRepo.exists(nodes.get("c1"), nodes.get("c2"))).isFalse();
    }

    @Test
    void findDependentsReturnsReverseDirection() {
        saveEdge("c1", "c3");
        saveEdge("c2", "c3");
        em.flush();

        List<PrerequisiteEdge> dependents =
                prereqRepo.findDirectDependentsOf(nodes.get("c3"));

        assertThat(dependents)
                .extracting(PrerequisiteEdge::contentId)
                .containsExactlyInAnyOrder(nodes.get("c1"), nodes.get("c2"));
    }

    @Test
    void findCyclePathEmptyWhenNoCycleWouldForm() {
        // Seed: c1 requires c2, c2 requires c3.
        saveEdge("c1", "c2");
        saveEdge("c2", "c3");
        em.flush();

        // Adding (c1, c3) — "c1 also requires c3" — is redundant but not a cycle.
        // Walk from prereq=c3: c3 has no outgoing "requires" edge → never reaches c1.
        List<ContentId> cycle =
                prereqRepo.findCyclePath(nodes.get("c1"), nodes.get("c3"));

        assertThat(cycle).isEmpty();
    }

    @Test
    void findCyclePathDirectTwoCycle() {
        // Seed: c2 requires c1.
        saveEdge("c2", "c1");
        em.flush();

        // Adding (c1, c2) — "c1 requires c2" — closes a 2-cycle (c1↔c2).
        // Walk from prereq=c2: edge (c2,c1) → reach c1.
        List<ContentId> cycle =
                prereqRepo.findCyclePath(nodes.get("c1"), nodes.get("c2"));

        assertThat(cycle).containsExactly(nodes.get("c2"), nodes.get("c1"));
    }

    @Test
    void findCyclePathThreeCycle() {
        // Seed: c1 requires c2, c2 requires c3.
        saveEdge("c1", "c2");
        saveEdge("c2", "c3");
        em.flush();

        // Adding (c3, c1) — "c3 requires c1" — closes a 3-cycle.
        // Walk from prereq=c1: c1→c2→c3 → reaches contentId=c3.
        List<ContentId> cycle =
                prereqRepo.findCyclePath(nodes.get("c3"), nodes.get("c1"));

        assertThat(cycle)
                .containsExactly(nodes.get("c1"), nodes.get("c2"), nodes.get("c3"));
    }

    @Test
    void findCyclePathEmptyOnDisjointSubgraph() {
        // Seed two unrelated chains: c1→c2 and c4→c5.
        saveEdge("c1", "c2");
        saveEdge("c4", "c5");
        em.flush();

        // Adding (c1, c4) — c1 would also require c4. The prereq side (c4)
        // walks forward and never reaches c1.
        List<ContentId> cycle =
                prereqRepo.findCyclePath(nodes.get("c1"), nodes.get("c4"));

        assertThat(cycle).isEmpty();
    }

    @Test
    void findCyclePathSelfReturnsSinglePath() {
        // Domain layer (Content.canAddPrerequisite) handles self-edge first,
        // but the adapter must remain safe if called directly.
        List<ContentId> cycle =
                prereqRepo.findCyclePath(nodes.get("c1"), nodes.get("c1"));

        assertThat(cycle).containsExactly(nodes.get("c1"));
    }

    @Test
    void duplicateEdgeRaisesDataIntegrityViolation() {
        saveEdge("c1", "c2");
        em.flush();

        assertThatThrownBy(
                        () -> {
                            saveEdge("c1", "c2");
                            em.flush();
                        })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @SpringBootApplication(
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class PrereqRepoITApp {}
}
