package com.plrs.infrastructure.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.user.BCryptHash;
import com.plrs.domain.user.Email;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserRepository;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for the {@link SpringDataUserRepository} adapter. Drives
 * the {@link UserRepository} port (not the adapter class directly) so that
 * if Spring fails to wire port→adapter, {@code @Autowired} would fail
 * before the tests run — that is itself an acceptance-criterion guarantee.
 *
 * <p>Each test generates a fresh email via {@link UUID} to stay isolated
 * from sibling tests without needing transactional rollback, which would
 * otherwise suppress the constraint-violation that the duplicate-email
 * test relies on (violations fire at commit).
 *
 * <p>Traces to: §3.a (adapter implements domain port), §3.c (users persistence).
 */
@SpringBootTest(
        classes = SpringDataUserRepositoryIT.UserRepoITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=plrs_ops"
        })
class SpringDataUserRepositoryIT extends PostgresTestBase {

    private static final String RAW_HASH =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";
    private static final Instant T0 = Instant.parse("2026-04-23T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static Email uniqueEmail() {
        return Email.of("user-" + UUID.randomUUID() + "@example.com");
    }

    private static User newUser(Email email) {
        return User.register(email, BCryptHash.of(RAW_HASH), CLOCK, "system");
    }

    @Test
    void savedUserCanBeFoundById() {
        User registered = newUser(uniqueEmail());

        User saved = userRepository.save(registered);
        var loaded = userRepository.findById(saved.id());

        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(registered);
        assertThat(loaded.get().email()).isEqualTo(registered.email());
    }

    @Test
    void savedUserCanBeFoundByEmail() {
        Email email = uniqueEmail();
        User registered = newUser(email);

        userRepository.save(registered);
        var loaded = userRepository.findByEmail(email);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo(registered.id());
    }

    @Test
    void existsByEmailReflectsStorageState() {
        Email email = uniqueEmail();

        assertThat(userRepository.existsByEmail(email)).isFalse();

        userRepository.save(newUser(email));

        assertThat(userRepository.existsByEmail(email)).isTrue();
    }

    @Test
    void savingUserWithTwoRolesWritesTwoJoinRows() {
        User twoRoleUser = newUser(uniqueEmail()).assignRole(Role.INSTRUCTOR, CLOCK);

        User saved = userRepository.save(twoRoleUser);

        User loaded = userRepository.findById(saved.id()).orElseThrow();
        assertThat(loaded.roles()).containsExactlyInAnyOrder(Role.STUDENT, Role.INSTRUCTOR);

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM plrs_ops.user_roles WHERE user_id = ?",
                        Integer.class,
                        saved.id().value());
        assertThat(count).isEqualTo(2);
    }

    @Test
    void addingRoleAndResavingPersistsTheNewRole() {
        User initial = newUser(uniqueEmail());
        userRepository.save(initial);

        User loaded = userRepository.findById(initial.id()).orElseThrow();
        User promoted = loaded.assignRole(Role.ADMIN, CLOCK);
        userRepository.save(promoted);

        User reloaded = userRepository.findById(initial.id()).orElseThrow();
        assertThat(reloaded.roles()).containsExactlyInAnyOrder(Role.STUDENT, Role.ADMIN);

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM plrs_ops.user_roles WHERE user_id = ?",
                        Integer.class,
                        initial.id().value());
        assertThat(count).isEqualTo(2);
    }

    @Test
    void duplicateEmailSaveRaisesDataIntegrityViolation() {
        Email shared = uniqueEmail();
        userRepository.save(newUser(shared));

        User duplicate = newUser(shared);

        assertThatThrownBy(() -> userRepository.save(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @SpringBootApplication(
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class UserRepoITApp {}
}
