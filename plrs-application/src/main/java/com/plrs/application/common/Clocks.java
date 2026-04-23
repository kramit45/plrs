package com.plrs.application.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Canonical {@link Clock} bean for the application. All use cases and
 * adapters that need the current instant should inject this bean rather
 * than calling {@link Clock#systemUTC()} directly, so tests can pin time
 * with a {@code Clock.fixed(...)} substitute without reflection or static
 * mocks.
 *
 * <p>The JWT infrastructure's own Clock bean (introduced in step 31) is
 * guarded with {@code @ConditionalOnMissingBean(Clock.class)}, so it
 * yields to this one once both configurations are on the classpath —
 * which is the case in the full application context. Keeping the
 * canonical bean here (closer to the domain) lets the infra-side bean
 * keep its self-contained fallback without forcing a cross-module
 * dependency on {@code spring-boot-autoconfigure}.
 */
@Configuration
public class Clocks {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
