package com.plrs.web.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Declares the JPA persistence boundary: entities and Spring Data repositories
 * both live under {@code com.plrs.infrastructure}. Conditional on
 * {@code spring.datasource.url} so the app can boot in early iterations before
 * a real database is wired up (steps 12–14).
 *
 * <p>Traces to: §3.a — infrastructure adapters isolated from the domain.
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.url")
@EnableJpaRepositories(basePackages = "com.plrs.infrastructure")
@EntityScan(basePackages = "com.plrs.infrastructure")
public class PersistenceConfig {}
