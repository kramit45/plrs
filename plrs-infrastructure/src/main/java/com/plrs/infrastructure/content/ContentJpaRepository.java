package com.plrs.infrastructure.content;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link ContentJpaEntity}. Package-private
 * API; application code depends on the domain-level
 * {@code com.plrs.domain.content.ContentRepository} port, which
 * {@link SpringDataContentRepository} implements in terms of this
 * interface.
 *
 * <p>The FR-13 keyword search is expressed as a pair of native queries
 * (data + count) over a {@code to_tsvector} of title and description.
 * This is deliberately not a derived-JPQL method because:
 *
 * <ul>
 *   <li>JPQL has no first-class tsvector / {@code @@} operator support;
 *   <li>the GIN index {@code idx_content_search} from V5 is only usable
 *       when the query expresses the tsvector/tsquery pair in SQL form.
 * </ul>
 *
 * <p>Relevance ranking uses {@code ts_rank} with {@code created_at DESC}
 * as a tiebreaker, matching the contract documented on
 * {@code ContentRepository.search}.
 */
public interface ContentJpaRepository extends JpaRepository<ContentJpaEntity, Long> {

    List<ContentJpaEntity> findByTopicId(Long topicId);

    boolean existsByTopicIdAndTitle(Long topicId, String title);

    @Query(
            value =
                    "SELECT c.* FROM plrs_ops.content c"
                            + " WHERE to_tsvector('english',"
                            + "                    c.title || ' ' || coalesce(c.description, ''))"
                            + "       @@ plainto_tsquery('english', :q)"
                            + " ORDER BY ts_rank("
                            + "            to_tsvector('english',"
                            + "              c.title || ' ' || coalesce(c.description, '')),"
                            + "            plainto_tsquery('english', :q)"
                            + "          ) DESC,"
                            + "          c.created_at DESC"
                            + " LIMIT :limit OFFSET :offset",
            nativeQuery = true)
    List<ContentJpaEntity> searchByTsQuery(
            @Param("q") String q, @Param("limit") int limit, @Param("offset") int offset);

    @Query(
            value =
                    "SELECT count(*) FROM plrs_ops.content c"
                            + " WHERE to_tsvector('english',"
                            + "                    c.title || ' ' || coalesce(c.description, ''))"
                            + "       @@ plainto_tsquery('english', :q)",
            nativeQuery = true)
    long countByTsQuery(@Param("q") String q);
}
