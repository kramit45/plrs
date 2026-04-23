package com.plrs.infrastructure.user;

import com.plrs.domain.user.Email;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Adapter implementing the domain-owned
 * {@link com.plrs.domain.user.UserRepository} port on top of Spring Data
 * JPA. Delegates every method to {@link UserJpaRepository} and converts
 * between the domain aggregate and the JPA entity via {@link UserMapper}.
 *
 * <p>Database uniqueness violations (duplicate email races past the
 * application-layer existence check) surface here as
 * {@link org.springframework.dao.DataIntegrityViolationException}. Those
 * are deliberately left unwrapped at this layer: the use case in step 34
 * catches them and maps to a domain-level outcome. Keeping the adapter
 * thin — pure translation, no policy — means every bit of business
 * behaviour stays testable in isolation at the service layer.
 *
 * <p>Traces to: §3.a (adapter implements domain port), §3.c (users persistence).
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataUserRepository implements UserRepository {

    private final UserJpaRepository jpa;
    private final UserMapper mapper;

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
}
