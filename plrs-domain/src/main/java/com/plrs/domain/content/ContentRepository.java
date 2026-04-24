package com.plrs.domain.content;

import com.plrs.domain.topic.TopicId;
import java.util.List;
import java.util.Optional;

/**
 * Port for loading and persisting {@link Content} aggregates. The
 * interface is declared in the domain module — alongside the aggregate
 * itself — so application services depend only on the domain abstraction.
 * The Spring Data JPA adapter that implements this port lives in the
 * infrastructure module.
 *
 * <p>The port exposes the surface needed for Iter 2 authoring and
 * browsing: save a draft (the adapter assigns the {@code BIGSERIAL} id),
 * fetch by id, list a topic's content, enforce the
 * {@code content_topic_title_uk} uniqueness pre-check, and run a paginated
 * keyword search backed by the V5 GIN full-text index. Update and delete
 * are deliberately out of scope — delete needs the cascade/FR-12
 * analysis that lands in Iter 4; the update path is deferred.
 *
 * <p>Traces to: §3.a (hexagonal — domain-owned ports), §3.c.1.3 (content
 * schema), FR-13 (paginated keyword search).
 */
public interface ContentRepository {

    /**
     * Persists a fresh content item. The adapter assigns the
     * {@code BIGSERIAL} {@link ContentId} and returns a fully-populated
     * {@link Content} whose other fields match the supplied draft.
     *
     * @param draft the pre-persistence content item; the draft's validation
     *     has already enforced the invariants the aggregate will hold
     * @return the persisted content with its server-assigned id
     */
    Content save(ContentDraft draft);

    /**
     * Loads a content item by its persistent identity.
     *
     * @return the content if present, or {@link Optional#empty()} otherwise
     */
    Optional<Content> findById(ContentId id);

    /**
     * Lists every content item attached to the given topic. Does not
     * paginate — topic-level content counts are small in scope (Iter 2
     * design envisions tens of items per topic at most). Pagination can
     * be introduced alongside a use case that needs it.
     */
    List<Content> findByTopicId(TopicId topicId);

    /**
     * Keyword search over title, description, and tags (FR-13). Pagination
     * is by ({@code pageSize}, {@code pageNumber}); the use-case layer is
     * responsible for clamping {@code pageSize} to {@code [1, 100]} and
     * defaulting it to {@code 20} so the port contract stays decoupled
     * from HTTP-layer policy.
     *
     * <p>Results are ordered by full-text search relevance
     * (V5 {@code ts_rank_cd} over the GIN index), with {@code created_at
     * DESC} as a tiebreaker so that, among equally-relevant hits, newer
     * content surfaces first.
     *
     * @param query the raw user-supplied query string; adapters quote or
     *     escape as needed for the underlying full-text engine
     * @param pageSize number of items per page, {@code >= 1}
     * @param pageNumber zero-based page index, {@code >= 0}
     * @return the page of matching content plus cursor metadata
     */
    SearchPage search(String query, int pageSize, int pageNumber);

    /**
     * Cheap uniqueness probe for authoring flows that need to enforce the
     * {@code content_topic_title_uk} UNIQUE (topic_id, title) constraint
     * (§3.c.1.3) without materialising the full aggregate.
     */
    boolean existsByTopicIdAndTitle(TopicId topicId, String title);
}
