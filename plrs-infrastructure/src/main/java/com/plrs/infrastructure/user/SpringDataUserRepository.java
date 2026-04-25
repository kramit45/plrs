package com.plrs.infrastructure.user;

import com.plrs.domain.user.Email;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter implementing the domain-owned
 * {@link com.plrs.domain.user.UserRepository} port on top of Spring Data
 * JPA. Delegates every method to {@link UserJpaRepository} and converts
 * between the domain aggregate and the JPA entity via {@link UserMapper}.
 *
 * <p>{@link #bumpSkillsVersion} runs a single native UPDATE inside the
 * caller's transaction (PROPAGATION.MANDATORY). The DB-side TRG-3
 * trigger (§3.b.5.3) ensures the column is monotonic.
 *
 * <p>Traces to: §3.a (adapter implements domain port), §3.c (users persistence),
 * §2.e.2.4.2 (TX-01 version bump).
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataUserRepository implements UserRepository {

    private final UserJpaRepository jpa;
    private final UserMapper mapper;

    @PersistenceContext private EntityManager em;

    public SpringDataUserRepository(UserJpaRepository jpa, UserMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public Optional<User> findById(UserId id) {
        return jpa.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return jpa.findByEmail(email.value()).map(mapper::toDomain);
    }

    @Override
    public boolean existsByEmail(Email email) {
        return jpa.existsByEmail(email.value());
    }

    @Override
    public User save(User user) {
        UserJpaEntity saved = jpa.save(mapper.toEntity(user));
        return mapper.toDomain(saved);
    }

    /**
     * Atomically bumps {@code users.user_skills_version} by 1. Must run
     * inside an existing transaction (mandated via
     * {@link Propagation#MANDATORY}); the calling use case
     * (SubmitQuizAttempt, step 90) is already {@code @Transactional}.
     * TRG-3 (§3.b.5.3) on the {@code users} table back-stops monotonicity.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void bumpSkillsVersion(UserId userId) {
        em.createNativeQuery(
                        "UPDATE plrs_ops.users"
                                + " SET user_skills_version = user_skills_version + 1"
                                + " WHERE id = :userId")
                .setParameter("userId", userId.value())
                .executeUpdate();
    }
}
