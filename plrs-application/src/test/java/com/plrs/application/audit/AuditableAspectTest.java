package com.plrs.application.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit-level tests for {@link AuditableAspect} that wire the aspect
 * around a hand-rolled service via Spring's
 * {@link AspectJProxyFactory}, so the @Around weaving runs without a
 * full {@code @SpringBootTest}.
 */
class AuditableAspectTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final UUID USER_UUID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** Implementation-side annotations — Spring AOP's @annotation
     * pointcut binds to the target method, not the interface. */
    static class Service {
        @Auditable(action = "DEMO_OK", entityType = "demo")
        public String run() {
            return "ok";
        }

        @Auditable(action = "DEMO_FAIL")
        public String fail() {
            throw new IllegalStateException("boom");
        }
    }

    private static class CountingRepo implements AuditRepository {
        boolean throwOnAppend = false;
        AuditEvent last;

        @Override
        public void append(AuditEvent event) {
            if (throwOnAppend) {
                throw new RuntimeException("audit failure");
            }
            this.last = event;
        }
    }

    private final CountingRepo repo = new CountingRepo();

    private Service proxied() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Service());
        factory.addAspect(new AuditableAspect(repo, CLOCK));
        return factory.getProxy();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void appendsAuditEventOnSuccessfulInvocation() {
        AuditRepository spyRepo = org.mockito.Mockito.mock(AuditRepository.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new Service());
        factory.addAspect(new AuditableAspect(spyRepo, CLOCK));
        Service service = factory.getProxy();

        service.run();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(spyRepo).append(captor.capture());
        AuditEvent ev = captor.getValue();
        assertThat(ev.action()).isEqualTo("DEMO_OK");
        assertThat(ev.entityType()).contains("demo");
        assertThat(ev.occurredAt()).isEqualTo(T0);
    }

    @Test
    void doesNotAuditWhenAuditedMethodThrows() {
        AuditRepository spyRepo = org.mockito.Mockito.mock(AuditRepository.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new Service());
        factory.addAspect(new AuditableAspect(spyRepo, CLOCK));
        Service service = factory.getProxy();

        assertThatThrownBy(service::fail).isInstanceOf(IllegalStateException.class);

        verify(spyRepo, never()).append(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noEntityTypeOnAnnotationYieldsEmptyEntityTypeOnEvent() {
        // Drive through a method whose @Auditable has no entityType.
        // We need a Service variant for this — declare a tiny ad-hoc
        // POJO with the right annotation and weave the aspect over it.
        @SuppressWarnings("unused")
        class NoEntityService {
            @Auditable(action = "NO_ENTITY")
            String run() {
                return "ok";
            }
        }
        AuditRepository spyRepo = org.mockito.Mockito.mock(AuditRepository.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new NoEntityService());
        factory.addAspect(new AuditableAspect(spyRepo, CLOCK));
        // Note: the proxy is reached via the concrete class because the
        // method is package-private — addAspect handles CGLIB.
        NoEntityService proxy = factory.getProxy();

        proxy.run();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(spyRepo).append(captor.capture());
        assertThat(captor.getValue().entityType()).isEmpty();
    }

    @Test
    void resolvesActorFromSecurityContextWhenAuthenticated() {
        var token =
                new UsernamePasswordAuthenticationToken(
                        USER_UUID.toString(),
                        null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
        SecurityContextHolder.getContext().setAuthentication(token);

        proxied().run();

        assertThat(repo.last).isNotNull();
        assertThat(repo.last.actorUserId()).contains(UserId.of(USER_UUID));
    }

    @Test
    void anonymousPrincipalProducesEmptyActor() {
        var anon =
                new AnonymousAuthenticationToken(
                        "key",
                        "anonymousUser",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anon);

        proxied().run();

        assertThat(repo.last).isNotNull();
        assertThat(repo.last.actorUserId()).isEmpty();
    }

    @Test
    void nonUuidPrincipalProducesEmptyActor() {
        var token =
                new UsernamePasswordAuthenticationToken(
                        "service-account",
                        null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
        SecurityContextHolder.getContext().setAuthentication(token);

        proxied().run();

        assertThat(repo.last).isNotNull();
        assertThat(repo.last.actorUserId()).isEmpty();
    }

    @Test
    void appendFailureDoesNotPropagateAndDoesNotMaskBusinessResult() {
        repo.throwOnAppend = true;

        String result = proxied().run();

        assertThat(result).isEqualTo("ok");
        // No exception escapes despite the repo throwing.
    }
}
