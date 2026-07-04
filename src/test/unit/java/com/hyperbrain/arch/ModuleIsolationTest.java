package com.hyperbrain.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit rules enforcing DDD module isolation.
 * These rules are structural invariants: they apply to every class added in the future,
 * not just the skeleton. Add new rules here as modules are implemented.
 */
@AnalyzeClasses(packages = "com.hyperbrain")
class ModuleIsolationTest {

    /**
     * Domain layers must never depend on infrastructure layers (within any module).
     * Domain logic is pure: it knows nothing about JPA, SQS, or HTTP.
     */
    @ArchTest
    static final ArchRule domain_free_of_infrastructure =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    /**
     * The gateway module only routes external calls to core application services.
     * It must not reach into sync, finance, learning, cognitive, prioritizer, or planner.
     */
    @ArchTest
    static final ArchRule gateway_only_accesses_core =
        noClasses()
            .that().resideInAPackage("com.hyperbrain.gateway..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.hyperbrain.sync..",
                "com.hyperbrain.finance..",
                "com.hyperbrain.learning..",
                "com.hyperbrain.cognitive..",
                "com.hyperbrain.prioritizer..",
                "com.hyperbrain.planner.."
            );

    /**
     * The cognitive (iaService) module is read-only with respect to other modules.
     * It may call application services or domain query ports, but must never
     * touch another module's infrastructure (no direct JPA writes, no SQS producers
     * belonging to other modules).
     */
    @ArchTest
    static final ArchRule cognitive_does_not_write_to_other_modules =
        noClasses()
            .that().resideInAPackage("com.hyperbrain.cognitive..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.hyperbrain.core.infrastructure..",
                "com.hyperbrain.sync.infrastructure..",
                "com.hyperbrain.finance.infrastructure..",
                "com.hyperbrain.learning.infrastructure..",
                "com.hyperbrain.prioritizer.infrastructure..",
                "com.hyperbrain.planner.infrastructure.."
            );

    /**
     * The shared pipeline (outbox relay, messaging ports) is a leaf: feature modules depend on it,
     * never the other way around. This also enforces that the OutboxWorker holds no business logic —
     * it structurally cannot reach into any module.
     */
    @ArchTest
    static final ArchRule shared_does_not_depend_on_feature_modules =
        noClasses()
            .that().resideInAPackage("com.hyperbrain.shared..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.hyperbrain.core..",
                "com.hyperbrain.sync..",
                "com.hyperbrain.finance..",
                "com.hyperbrain.learning..",
                "com.hyperbrain.cognitive..",
                "com.hyperbrain.prioritizer..",
                "com.hyperbrain.planner..",
                "com.hyperbrain.gateway.."
            );

    /**
     * The sync SQS adapter (Consumer) must not reach into other feature modules' internals;
     * it only deserializes and delegates to its own application layer.
     */
    @ArchTest
    static final ArchRule sync_infrastructure_does_not_depend_on_other_modules =
        noClasses()
            .that().resideInAPackage("com.hyperbrain.sync.infrastructure..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.hyperbrain.core..",
                "com.hyperbrain.finance..",
                "com.hyperbrain.learning..",
                "com.hyperbrain.cognitive..",
                "com.hyperbrain.prioritizer..",
                "com.hyperbrain.planner..",
                "com.hyperbrain.gateway.."
            );
}
