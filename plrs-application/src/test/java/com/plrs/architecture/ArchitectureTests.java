package com.plrs.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
}
