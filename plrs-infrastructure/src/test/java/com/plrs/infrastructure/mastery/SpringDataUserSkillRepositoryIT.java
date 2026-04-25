package com.plrs.infrastructure.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.mastery.UserSkill;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link SpringDataUserSkillRepository} and the
 * new {@code bumpSkillsVersion} method on
 * {@link com.plrs.domain.user.UserRepository}.
 *
 * <p>Traces to: §3.c.1.4, §3.c.5.7, §2.e.2.4.2 (TX-01).
 */
@SpringBootTest(
        classes = SpringDataUserSkillRepositoryIT.UserSkillRepoITApp.class,
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
class SpringDataUserSkillRepositoryIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private UserSkillRepository userSkillRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    private UserId userId;
    private TopicId topicA;
    private TopicId topicB;

    @BeforeEach
    void seed() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            UUID uid = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.users"
                            + " (id, email, password_hash, created_at, updated_at, created_by)"
                            + " VALUES (?, ?, ?, NOW(), NOW(), 'test')")) {
                ps.setObject(1, uid);
                ps.setString(2, "skill-" + uid + "@example.com");
                ps.setString(3, VALID_BCRYPT);
                ps.executeUpdate();
            }
            userId = UserId.of(uid);

            topicA = insertTopic(conn);
            topicB = insertTopic(conn);
        }
    }

    private static TopicId insertTopic(Connection conn) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                        + " VALUES (?, 'test') RETURNING topic_id")) {
            ps.setString(1, "skill-topic-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return TopicId.of(rs.getLong("topic_id"));
            }
        }
    }

    private UserSkill seedSkill(TopicId topic, double mastery, String confidence) {
        return UserSkill.rehydrate(
                userId,
                topic,
                MasteryScore.of(mastery),
                new BigDecimal(confidence),
                Instant.parse("2026-04-25T10:00:00Z"));
    }

    @Test
    void upsertNewSkillInserts() {
        userSkillRepository.upsert(seedSkill(topicA, 0.7, "0.300"));
        em.flush();

        UserSkill loaded = userSkillRepository.find(userId, topicA).orElseThrow();
        assertThat(loaded.mastery().value()).isEqualTo(0.7);
        assertThat(loaded.confidence()).isEqualByComparingTo("0.300");
    }

    @Test
    void upsertExistingSkillUpdates() {
        userSkillRepository.upsert(seedSkill(topicA, 0.5, "0.100"));
        em.flush();

        userSkillRepository.upsert(seedSkill(topicA, 0.8, "0.500"));
        em.flush();
        em.clear();

        UserSkill loaded = userSkillRepository.find(userId, topicA).orElseThrow();
        assertThat(loaded.mastery().value()).isEqualTo(0.8);
        assertThat(loaded.confidence()).isEqualByComparingTo("0.500");
    }

    @Test
    void findRoundTrips() {
        UserSkill saved = userSkillRepository.upsert(seedSkill(topicA, 0.42, "0.250"));
        em.flush();
        em.clear();

        UserSkill loaded = userSkillRepository.find(userId, topicA).orElseThrow();
        assertThat(loaded).isEqualTo(saved);
        assertThat(loaded.mastery().value()).isEqualTo(0.42);
    }

    @Test
    void findByUserReturnsAllTopicsForUser() {
        userSkillRepository.upsert(seedSkill(topicA, 0.6, "0.100"));
        userSkillRepository.upsert(seedSkill(topicB, 0.7, "0.200"));
        em.flush();

        List<UserSkill> skills = userSkillRepository.findByUser(userId);

        assertThat(skills).hasSize(2);
        assertThat(skills).extracting(UserSkill::topicId).containsExactlyInAnyOrder(topicA, topicB);
    }

    @Test
    void upsertWithMasteryOutOfRangeRejectedByDomain() {
        // Defence-in-depth: domain MasteryScore.of rejects values > 1
        // before the DB CHECK ever sees them.
        assertThatThrownBy(() -> seedSkill(topicA, 1.5, "0.100"))
                .isInstanceOf(com.plrs.domain.common.DomainValidationException.class);
    }

    @Test
    void bumpSkillsVersionIncrementsAtomically() {
        Long before =
                ((Number)
                                em.createNativeQuery(
                                                "SELECT user_skills_version FROM plrs_ops.users"
                                                        + " WHERE id = :uid")
                                        .setParameter("uid", userId.value())
                                        .getSingleResult())
                        .longValue();

        userRepository.bumpSkillsVersion(userId);
        em.flush();
        em.clear();

        Long after =
                ((Number)
                                em.createNativeQuery(
                                                "SELECT user_skills_version FROM plrs_ops.users"
                                                        + " WHERE id = :uid")
                                        .setParameter("uid", userId.value())
                                        .getSingleResult())
                        .longValue();

        assertThat(after).isEqualTo(before + 1);
    }

    @SpringBootApplication(
            scanBasePackages = {
                "com.plrs.infrastructure.mastery",
                "com.plrs.infrastructure.user"
            },
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    @org.springframework.boot.autoconfigure.domain.EntityScan(
            basePackages = {
                "com.plrs.infrastructure.mastery",
                "com.plrs.infrastructure.user"
            })
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
            basePackages = {
                "com.plrs.infrastructure.mastery",
                "com.plrs.infrastructure.user"
            })
    static class UserSkillRepoITApp {}
}
