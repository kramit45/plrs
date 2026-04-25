package com.plrs.infrastructure.content;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.user.UserId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Array;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Adapter implementing the domain-owned
 * {@link com.plrs.domain.content.PrerequisiteRepository} port. CRUD
 * delegates to {@link PrerequisiteJpaRepository}; the cycle walk uses
 * {@link JdbcTemplate} against the V7 {@code prerequisites} table with
 * a recursive CTE.
 *
 * <p>Cycle CTE semantics: an edge {@code (content, prereq)} reads
 * "content requires prereq". Adding it would close a cycle iff there is
 * already a path from {@code prereq} back to {@code content} through the
 * existing edges (following the "requires" direction). The CTE walks
 * forward from {@code prereq}, joining on {@code content_id = current
 * node} and yielding {@code prereq_content_id} as the next hop. A
 * safety bound of {@code path length < 100} prevents runaway recursion
 * in the (impossible-by-design) case that the table itself contains a
 * cycle.
 *
 * <p>Not declared {@code final}: Spring Boot's observation / metrics
 * {@code AbstractAdvisingBeanPostProcessor} CGLIB-subclasses every
 * {@code @Component} bean. Same constraint as the other Spring Data
 * adapters in this module.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 *
 * <p>Traces to: §3.a (adapter implements domain port), §3.c.1.3
 * (prerequisites schema), §2.e.2.5 (CFD-4 application-side cycle
 * detection), §3.b.5.5 (recursive-CTE pattern reused in nightly check).
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataPrerequisiteRepository implements PrerequisiteRepository {

    private static final String CYCLE_CTE =
            "WITH RECURSIVE reachable(node, path) AS ("
                    + "  SELECT CAST(? AS BIGINT) AS node,"
                    + "         ARRAY[CAST(? AS BIGINT)] AS path"
                    + "  UNION ALL"
                    + "  SELECT p.prereq_content_id,"
                    + "         r.path || p.prereq_content_id"
                    + "    FROM plrs_ops.prerequisites p"
                    + "    JOIN reachable r ON p.content_id = r.node"
                    + "   WHERE NOT (p.prereq_content_id = ANY(r.path))"
                    + "     AND array_length(r.path, 1) < 100"
                    + ") SELECT path FROM reachable WHERE node = ? LIMIT 1";

    private final PrerequisiteJpaRepository jpa;
    private final JdbcTemplate jdbc;

    @PersistenceContext private EntityManager em;

    public SpringDataPrerequisiteRepository(PrerequisiteJpaRepository jpa, DataSource dataSource) {
        this.jpa = jpa;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public PrerequisiteEdge save(PrerequisiteEdge edge) {
        PrerequisiteJpaEntity entity =
                new PrerequisiteJpaEntity(
                        edge.contentId().value(),
                        edge.prereqContentId().value(),
                        edge.addedAt(),
                        edge.addedBy().map(UserId::value).orElse(null));
        // Use persist() rather than jpa.save() because Spring Data's merge()
        // semantics for composite-PK entities silently update an existing row
        // — the IT's duplicate-edge case needs a constraint violation. The
        // composite PK on (content_id, prereq_content_id) catches the dup at
        // INSERT/flush time and the @Repository advice translates the
        // resulting JPA exception into DataIntegrityViolationException.
        em.persist(entity);
        return edge;
    }

    @Override
    public void remove(ContentId contentId, ContentId prereqContentId) {
        PrerequisiteEdgeId id =
                new PrerequisiteEdgeId(contentId.value(), prereqContentId.value());
        if (jpa.existsById(id)) {
            jpa.deleteById(id);
        }
    }

    @Override
    public List<PrerequisiteEdge> findDirectPrerequisitesOf(ContentId contentId) {
        return jpa.findByContentId(contentId.value()).stream().map(this::toDomain).toList();
    }

    @Override
    public List<PrerequisiteEdge> findDirectDependentsOf(ContentId contentId) {
        return jpa.findByPrereqContentId(contentId.value()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean exists(ContentId contentId, ContentId prereqContentId) {
        return jpa.existsByContentIdAndPrereqContentId(
                contentId.value(), prereqContentId.value());
    }

    @Override
    public List<ContentId> findCyclePath(ContentId content, ContentId prereq) {
        if (content.equals(prereq)) {
            return List.of(content);
        }
        Long contentValue = content.value();
        Long prereqValue = prereq.value();
        List<List<ContentId>> rows =
                jdbc.query(
                        CYCLE_CTE,
                        ps -> {
                            ps.setLong(1, prereqValue);
                            ps.setLong(2, prereqValue);
                            ps.setLong(3, contentValue);
                        },
                        (rs, rowNum) -> {
                            Array sqlArray = rs.getArray("path");
                            if (sqlArray == null) {
                                return List.<ContentId>of();
                            }
                            Object raw = sqlArray.getArray();
                            Long[] longs = (Long[]) raw;
                            return java.util.Arrays.stream(longs).map(ContentId::of).toList();
                        });
        return rows.isEmpty() ? List.of() : rows.get(0);
    }

    private PrerequisiteEdge toDomain(PrerequisiteJpaEntity entity) {
        return new PrerequisiteEdge(
                ContentId.of(entity.getContentId()),
                ContentId.of(entity.getPrereqContentId()),
                entity.getAddedAt(),
                Optional.ofNullable(entity.getAddedBy()).map(UserId::of));
    }
}
