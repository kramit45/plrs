package com.plrs.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.plrs.application.outbox.OutboxRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.transaction.annotation.Transactional;

// Scope note: hosted in plrs-application; at this step the test classpath only
// sees com.plrs.domain and com.plrs.application classes. The harness will be
// promoted to plrs-web in steps 9/10 if the layering rules need to inspect
// infrastructure and web classes too.

/**
 * ArchUnit module-boundary rules for the PLRS codebase.
 *
 * <ul>
 *   <li>{@code classes_are_either_public_or_not_public} — smoke rule.
 *   <li>{@code domain_must_not_depend_on_frameworks} — enforces that
 *       {@code com.plrs.domain} stays on the Java standard library; no Spring,
 *       JPA, Jackson, Hibernate or Lombok imports are allowed so the domain
 *       model remains portable and readable as vanilla Java.
 *   <li>{@code module_dependency_direction} — enforces the layered dependency
 *       arrow {@code domain ← application ← infrastructure ← web}; lower
 *       layers may not reach into higher ones.
 * </ul>
 *
 * <p>Traces to: §3.a — module boundary enforcement.
 */
@AnalyzeClasses(packages = "com.plrs", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    @ArchTest
    static final ArchRule classes_are_either_public_or_not_public =
            classes()
                    .that()
                    .resideInAPackage("..plrs..")
                    .should()
                    .bePublic()
                    .orShould()
                    .notBePublic()
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domain_must_not_depend_on_frameworks =
            noClasses()
                    .that()
                    .resideInAPackage("..plrs.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.fasterxml.jackson..",
                            "org.springframework.data..",
                            "lombok..",
                            "org.hibernate..")
                    .because("domain must remain framework-free per §3.a")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule module_dependency_direction =
            layeredArchitecture()
                    .consideringAllDependencies()
                    .layer("Domain")
                    .definedBy("com.plrs.domain..")
                    .layer("Application")
                    .definedBy("com.plrs.application..")
                    .layer("Infrastructure")
                    .definedBy("com.plrs.infrastructure..")
                    .layer("Web")
                    .definedBy("com.plrs.web..")
                    .whereLayer("Web")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Infrastructure")
                    .mayOnlyBeAccessedByLayers("Web")
                    .whereLayer("Application")
                    .mayOnlyBeAccessedByLayers("Infrastructure", "Web")
                    .whereLayer("Domain")
                    .mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Web")
                    .withOptionalLayers(true);

    /**
     * TX-01: outbox writes must commit in the same transaction as the
     * business state-change (§2.e.3.6). The structural surrogate is
     * "any application class that depends on {@link OutboxRepository}
     * must be marked {@code @Transactional} at the class level". The
     * exception is the adapter package itself ({@code SpringDataOutboxRepository}
     * is the implementation, not a caller); ArchUnit's package filter
     * keeps the rule scoped to {@code plrs.application}, where every
     * caller is a use case.
     */
    private static final DescribedPredicate<JavaClass> DEPENDS_ON_OUTBOX_REPOSITORY =
            new DescribedPredicate<>("depend on OutboxRepository") {
                @Override
                public boolean test(JavaClass clazz) {
                    return clazz.getDirectDependenciesFromSelf().stream()
                            .anyMatch(
                                    d ->
                                            d.getTargetClass()
                                                    .isAssignableTo(OutboxRepository.class));
                }
            };

    @ArchTest
    static final ArchRule classes_using_OutboxRepository_must_be_transactional =
            classes()
                    .that(DEPENDS_ON_OUTBOX_REPOSITORY)
                    .and()
                    .resideInAPackage("..plrs.application..")
                    .and()
                    .areNotInterfaces()
                    .should()
                    .beAnnotatedWith(Transactional.class)
                    .because(
                            "TX-01 (§2.e.3.6): outbox writes must share the"
                                    + " transaction of the business write")
                    .allowEmptyShould(true);
}
