package com.plrs.infrastructure.security.jwt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link JwtProperties} as a bean so the JWT infrastructure can
 * inject typed configuration. Kept as a dedicated configuration class
 * rather than marking {@link JwtProperties} with {@code @Component} so the
 * binding remains explicit and discoverable.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {}
