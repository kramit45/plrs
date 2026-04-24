package com.plrs.infrastructure.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicDraft;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for the {@link SpringDataTopicRepository} adapter.
 * Drives the {@link TopicRepository} port (not the adapter class directly)
 * so that Spring wiring port→adapter is itself an acceptance-criterion
 * guarantee: if autowiring resolved to a different bean, the field
 * injection would fail at context start.
 *
 * <p>Runs under {@link Transactional} so each test rolls back and sibling
 * tests stay isolated without needing per-test unique names. The
 * duplicate-name test still surfaces
 * {@link DataIntegrityViolationException} because Hibernate's IDENTITY
 * strategy executes the INSERT immediately on {@code save(...)} to
 * retrieve the generated key — the unique constraint fires at statement
 * time, not at commit.
 *
 * <p>Traces to: §3.a (adapter implements domain port), §3.c.1.3
 * (topics persistence).
 */
@SpringBootTest(
        classes = SpringDataTopicRepositoryIT.TopicRepoITApp.class,
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
class SpringDataTopicRepositoryIT extends PostgresTestBase {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);

    @Autowired private TopicRepository topicRepository;

    private static String uniqueName() {
        return "topic-" + UUID.randomUUID();
    }

    private static TopicDraft rootDraft(String name) {
        return new TopicDraft(name, null, Optional.empty(), AuditFields.initial("system", CLOCK));
    }

    private static TopicDraft childDraft(String name, TopicId parent) {
        return new TopicDraft(
                name, null, Optional.of(parent), AuditFields.initial("system", CLOCK));
    }

    @Test
    void saveDraftAssignsIdAndReturnsTopic() {
        Topic saved = topicRepository.save(rootDraft(uniqueName()));

        assertThat(saved.id()).isNotNull();
        assertThat(saved.id().value()).isPositive();
        assertThat(saved.parentTopicId()).isEmpty();
    }

    @Test
    void findByIdReturnsPersistedTopic() {
        String name = uniqueName();
        Topic saved = topicRepository.save(rootDraft(name));

        Optional<Topic> loaded = topicRepository.findById(saved.id());

        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo(saved.id());
        assertThat(loaded.get().name()).isEqualTo(name);
    }

    @Test
    void findByNameReturnsPersistedTopic() {
        String name = uniqueName();
        Topic saved = topicRepository.save(rootDraft(name));

        Optional<Topic> loaded = topicRepository.findByName(name);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo(saved.id());
    }

    @Test
    void existsByNameReflectsStorageState() {
        String name = uniqueName();

        assertThat(topicRepository.existsByName(name)).isFalse();

        topicRepository.save(rootDraft(name));

        assertThat(topicRepository.existsByName(name)).isTrue();
    }

    @Test
    void findChildrenOfReturnsDirectChildrenOnly() {
        Topic parent = topicRepository.save(rootDraft(uniqueName()));
        Topic grandparent = topicRepository.save(rootDraft(uniqueName()));
        Topic childA = topicRepository.save(childDraft(uniqueName(), parent.id()));
        Topic childB = topicRepository.save(childDraft(uniqueName(), parent.id()));
        topicRepository.save(childDraft(uniqueName(), grandparent.id()));

        var children = topicRepository.findChildrenOf(parent.id());

        assertThat(children)
                .extracting(Topic::id)
                .containsExactlyInAnyOrder(childA.id(), childB.id());
    }

    @Test
    void findRootTopicsReturnsTopicsWithNullParent() {
        Topic rootA = topicRepository.save(rootDraft(uniqueName()));
        Topic rootB = topicRepository.save(rootDraft(uniqueName()));
        topicRepository.save(childDraft(uniqueName(), rootA.id()));

        var roots = topicRepository.findRootTopics();

        assertThat(roots).extracting(Topic::id).contains(rootA.id(), rootB.id());
        assertThat(roots).allSatisfy(t -> assertThat(t.parentTopicId()).isEmpty());
    }

    @Test
    void saveDuplicateNameRaisesDataIntegrityViolation() {
        String name = uniqueName();
        topicRepository.save(rootDraft(name));

        assertThatThrownBy(() -> topicRepository.save(rootDraft(name)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @SpringBootApplication(
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class TopicRepoITApp {}
}
