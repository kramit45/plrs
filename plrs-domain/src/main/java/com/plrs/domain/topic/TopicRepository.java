package com.plrs.domain.topic;

import java.util.List;
import java.util.Optional;

/**
 * Port for loading and persisting {@link Topic} aggregates. The interface is
 * declared in the domain module — alongside the aggregate itself — so
 * application services depend only on the domain abstraction. The Spring
 * Data JPA adapter that implements this port lives in the infrastructure
 * module.
 *
 * <p>The port exposes the surface needed for Iter 2 authoring and
 * hierarchy navigation: save a draft (the adapter assigns the
 * {@code BIGSERIAL} id), fetch by id or by the unique name, probe for name
 * existence without materialising the aggregate, list a parent's children,
 * and list the root topics. Update and delete are deliberately out of
 * scope — FR-12 blocks topic delete while content references it, and the
 * update path is deferred to a later iteration.
 *
 * <p>Traces to: §3.a (hexagonal — domain-owned ports), §3.c.1.3 (topics
 * table with {@code topics_name_uk} unique constraint and self-FK).
 */
public interface TopicRepository {

    /**
     * Persists a fresh topic. The adapter assigns the {@code BIGSERIAL}
     * {@link TopicId} and returns a fully-populated {@link Topic} whose
     * other fields match the supplied draft.
     *
     * @param draft the pre-persistence topic; the draft's validation has
     *     already enforced the invariants the aggregate will hold
     * @return the persisted topic with its server-assigned id
     */
    Topic save(TopicDraft draft);

    /**
     * Loads a topic by its persistent identity.
     *
     * @return the topic if present, or {@link Optional#empty()} otherwise
     */
    Optional<Topic> findById(TopicId id);

    /**
     * Loads a topic by its unique name per the {@code topics_name_uk}
     * constraint (§3.c.1.3). The adapter is expected to match the stored
     * (already-trimmed) form exactly; callers should pass the name as the
     * user supplied it and the adapter normalises as needed.
     *
     * @return the topic if present, or {@link Optional#empty()} otherwise
     */
    Optional<Topic> findByName(String name);

    /**
     * Cheap uniqueness probe for authoring flows that only need to know
     * whether a name is already taken without materialising the full
     * aggregate.
     */
    boolean existsByName(String name);

    /**
     * Lists the direct children of the given parent topic — that is, every
     * topic whose {@link Topic#parentTopicId()} equals {@code parentId}.
     * Does not walk transitively; callers that need the full subtree
     * compose repeated calls or add a dedicated method later.
     */
    List<Topic> findChildrenOf(TopicId parentId);

    /**
     * Lists the root topics — every topic whose {@code parent_topic_id}
     * is SQL {@code NULL} (i.e., {@link Topic#parentTopicId()} is
     * {@link Optional#empty()}).
     */
    List<Topic> findRootTopics();
}
