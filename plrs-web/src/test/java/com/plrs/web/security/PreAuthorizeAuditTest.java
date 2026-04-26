package com.plrs.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.a.3.1 RBAC matrix sweep: scans every {@code @Controller} /
 * {@code @RestController} in the {@code com.plrs.web} package tree and
 * asserts that every mutating handler ({@code @PostMapping},
 * {@code @PutMapping}, {@code @DeleteMapping}, {@code @PatchMapping})
 * carries a {@link PreAuthorize} either on the method itself or on its
 * declaring class.
 *
 * <p>An explicit allowlist captures the deliberately public mutating
 * endpoints (registration, login, logout, password-reset request +
 * confirm) — these reach unauthenticated callers by design. Any
 * future controller that needs to be public must be added to the
 * allowlist with a comment justifying the exception.
 *
 * <p>The check is reflective rather than HTTP-level so it runs as a
 * fast unit test (no Spring context boot, no mocks) and surfaces
 * gaps the moment a new endpoint lands.
 *
 * <p>Traces to: §4.a.3.1, §7.
 */
class PreAuthorizeAuditTest {

    /**
     * Endpoints intentionally exposed without {@code @PreAuthorize}.
     * Format: {@code FQCN#methodName}. Each entry should be a
     * deliberate public surface — anyone adding a new entry should
     * leave a comment explaining why.
     */
    private static final Set<String> PUBLIC_ALLOWLIST = Set.of(
            // Public auth surface — anonymous registration + login.
            "com.plrs.web.auth.AuthController#register",
            "com.plrs.web.auth.AuthController#login",
            "com.plrs.web.auth.AuthController#logout",
            "com.plrs.web.auth.AuthFormController#submit",
            // FR-04: anonymous-allowed reset request + confirm so locked-out
            // users can recover; the token itself authorises confirm.
            "com.plrs.web.auth.PasswordResetController#request",
            "com.plrs.web.auth.PasswordResetController#confirm");

    private static final List<Class<? extends java.lang.annotation.Annotation>> MUTATING =
            List.of(PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class);

    @Test
    void everyMutatingControllerMethodIsGuardedOrAllowlisted() {
        Set<String> unguarded = new LinkedHashSet<>();
        for (Class<?> controller : findControllers()) {
            boolean classGuarded = controller.isAnnotationPresent(PreAuthorize.class);
            for (Method m : controller.getDeclaredMethods()) {
                if (!isMutating(m)) {
                    continue;
                }
                boolean methodGuarded =
                        AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class) != null;
                if (classGuarded || methodGuarded) {
                    continue;
                }
                String fqn = controller.getName() + "#" + m.getName();
                if (!PUBLIC_ALLOWLIST.contains(fqn)) {
                    unguarded.add(fqn);
                }
            }
        }
        assertThat(unguarded)
                .as(
                        "Mutating endpoints lacking @PreAuthorize and not on the public"
                                + " allowlist (add @PreAuthorize or — only when intentionally"
                                + " public — an entry in PUBLIC_ALLOWLIST with a justifying comment)")
                .isEmpty();
    }

    @Test
    void allowlistEntriesPointToRealMethods() {
        Set<String> stale = new HashSet<>();
        for (String entry : PUBLIC_ALLOWLIST) {
            int hash = entry.indexOf('#');
            String className = entry.substring(0, hash);
            String methodName = entry.substring(hash + 1);
            try {
                Class<?> c = Class.forName(className);
                boolean hasMethod =
                        java.util.Arrays.stream(c.getDeclaredMethods())
                                .anyMatch(m -> m.getName().equals(methodName));
                if (!hasMethod) {
                    stale.add(entry);
                }
            } catch (ClassNotFoundException e) {
                stale.add(entry);
            }
        }
        assertThat(stale)
                .as("PUBLIC_ALLOWLIST entries that no longer correspond to a real method")
                .isEmpty();
    }

    private static boolean isMutating(Method m) {
        for (Class<? extends java.lang.annotation.Annotation> ann : MUTATING) {
            if (AnnotatedElementUtils.findMergedAnnotation(m, ann) != null) {
                return true;
            }
        }
        return false;
    }

    private static Set<Class<?>> findControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents("com.plrs.web").stream()
                .map(BeanDefinition::getBeanClassName)
                .map(
                        name -> {
                            try {
                                return Class.forName(name);
                            } catch (ClassNotFoundException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                .collect(Collectors.toSet());
    }
}
