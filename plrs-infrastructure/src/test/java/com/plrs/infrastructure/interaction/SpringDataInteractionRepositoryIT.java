package com.plrs.infrastructure.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.interaction.InteractionEvent;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.interaction.Rating;
import com.plrs.domain.user.UserId;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
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
 * Integration test for {@link SpringDataInteractionRepository}. Drives
 * the {@link InteractionRepository} port to confirm Spring wires the
 * adapter, exercises both the save path (composite-PK semantics) and
 * the {@code existsViewSince} debounce read path.
 *
 * <p>Topic + content + user fixtures are seeded via raw SQL so the
 * nested {@code @SpringBootApplication} scan stays narrow.
 *
 * <p>Traces to: §3.a, §3.c.1.4, FR-15 / FR-16 / FR-17.
 */
@SpringBootTest(
        classes = SpringDataInteractionRepositoryIT.InteractionRepoITApp.class,
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
class SpringDataInteractionRepositoryIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private InteractionRepository interactionRepository;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    private UserId userId;
    private ContentId contentId;

    @BeforeEach
    void seedFixtures() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            UUID uid = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.users"
                            + " (id, email, password_hash, created_at, updated_at, created_by)"
                            + " VALUES (?, ?, ?, NOW(), NOW(), 'test')")) {
                ps.setObject(1, uid);
                ps.setString(2, "interaction-" + uid + "@example.com");
                ps.setString(3, VALID_BCRYPT);
                ps.executeUpdate();
            }
            userId = UserId.of(uid);

            long topicId;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                            + " VALUES (?, 'test') RETURNING topic_id")) {
                ps.setString(1, "interaction-topic-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    topicId = rs.getLong("topic_id");
                }
            }

            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.content"
                            + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                            + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 10, 'https://x.y')"
                            + " RETURNING content_id")) {
                ps.setLong(1, topicId);
                ps.setString(2, "interaction-content-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    contentId = ContentId.of(rs.getLong("content_id"));
                }
            }
        }
    }

    @Test
    void saveThenExistsViewSinceReadsBackForRecentView() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        interactionRepository.save(
                InteractionEvent.view(
                        userId, contentId, t0, Optional.of(30), Optional.empty()));
        em.flush();

        assertThat(interactionRepository.existsViewSince(userId, contentId, t0.minusSeconds(60)))
                .isTrue();
    }

    @Test
    void existsViewSinceFalseIfViewOlderThanSince() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        interactionRepository.save(
                InteractionEvent.view(
                        userId, contentId, t0, Optional.empty(), Optional.empty()));
        em.flush();

        assertThat(interactionRepository.existsViewSince(userId, contentId, t0.plusSeconds(1)))
                .isFalse();
    }

    @Test
    void existsViewSinceFalseForDifferentEventType() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        // Save a BOOKMARK at t0 — should not count as a recent VIEW.
        interactionRepository.save(
                InteractionEvent.bookmark(userId, contentId, t0, Optional.empty()));
        em.flush();

        assertThat(interactionRepository.existsViewSince(userId, contentId, t0.minusSeconds(60)))
                .isFalse();
    }

    @Test
    void existsViewSinceScopedToUserAndContent() throws SQLException {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        interactionRepository.save(
                InteractionEvent.view(
                        userId, contentId, t0, Optional.empty(), Optional.empty()));
        em.flush();

        // Different user, same content → no view.
        UUID otherUid = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection();
                var ps = conn.prepareStatement(
                        "INSERT INTO plrs_ops.users"
                                + " (id, email, password_hash, created_at, updated_at, created_by)"
                                + " VALUES (?, ?, ?, NOW(), NOW(), 'test')")) {
            ps.setObject(1, otherUid);
            ps.setString(2, "other-" + otherUid + "@example.com");
            ps.setString(3, VALID_BCRYPT);
            ps.executeUpdate();
        }
        UserId otherUserId = UserId.of(otherUid);

        assertThat(
                        interactionRepository.existsViewSince(
                                otherUserId, contentId, t0.minusSeconds(60)))
                .isFalse();
    }

    @Test
    void saveViewWithDwellPersistsAndReadsBack() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        interactionRepository.save(
                InteractionEvent.view(
                        userId, contentId, t0, Optional.of(45), Optional.of("ua=test")));
        em.flush();

        assertThat(interactionRepository.existsViewSince(userId, contentId, t0.minusSeconds(60)))
                .isTrue();
    }

    @Test
    void saveRateWithRatingPersists() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        interactionRepository.save(
                InteractionEvent.rate(
                        userId, contentId, t0, Rating.of(4), Optional.empty()));
        em.flush();
    }

    @Test
    void saveDuplicateCompositeKeyRaisesDataIntegrityViolation() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        interactionRepository.save(
                InteractionEvent.view(
                        userId, contentId, t0, Optional.empty(), Optional.empty()));
        em.flush();

        assertThatThrownBy(
                        () -> {
                            interactionRepository.save(
                                    InteractionEvent.view(
                                            userId,
                                            contentId,
                                            t0,
                                            Optional.empty(),
                                            Optional.empty()));
                            em.flush();
                        })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @SpringBootApplication(
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class InteractionRepoITApp {}
}
