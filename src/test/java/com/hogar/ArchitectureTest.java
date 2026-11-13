package com.hogar;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reglas de arquitectura de CLAUDE.md, verificadas en cada build.
 * {@code allowEmptyShould} permite que pasen mientras las features aún no existen.
 */
@AnalyzeClasses(packages = "com.hogar", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final Pattern FEATURE_LAYER = Pattern.compile("^com\\.hogar\\.([a-z]+)\\.([a-z]+).*");
    private static final String SHARED = "shared";
    /** Capas de una feature que otra feature puede usar: su contrato público. */
    private static final Set<String> PUBLIC_LAYERS = Set.of("service", "dto");

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersDoNotExposeEntities = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule servicesDoNotDependOnControllers = noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("..controller..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule entitiesLiveInDomain = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..domain..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule sharedDoesNotDependOnFeatures = classes()
            .that().resideInAPackage("com.hogar.shared..")
            .should(new ArchCondition<>("not depend on any feature package") {
                @Override
                public void check(JavaClass origin, ConditionEvents events) {
                    for (Dependency dep : origin.getDirectDependenciesFromSelf()) {
                        if (featureOf(dep.getTargetClass()).isPresent()) {
                            events.add(SimpleConditionEvent.violated(dep, dep.getDescription()));
                        }
                    }
                }
            });

    @ArchTest
    static final ArchRule featuresOnlyTalkThroughPublicContracts = classes()
            .that().resideInAPackage("com.hogar..")
            .should(new ArchCondition<>("only depend on other features' service/dto packages") {
                @Override
                public void check(JavaClass origin, ConditionEvents events) {
                    Optional<String> originFeature = featureOf(origin);
                    if (originFeature.isEmpty()) {
                        return;
                    }
                    for (Dependency dep : origin.getDirectDependenciesFromSelf()) {
                        Matcher target = FEATURE_LAYER.matcher(dep.getTargetClass().getName());
                        if (!target.matches() || SHARED.equals(target.group(1))) {
                            continue;
                        }
                        boolean otherFeature = !target.group(1).equals(originFeature.get());
                        if (otherFeature && !PUBLIC_LAYERS.contains(target.group(2))) {
                            events.add(SimpleConditionEvent.violated(dep, dep.getDescription()));
                        }
                    }
                }
            })
            .allowEmptyShould(true);

    /** Feature de una clase ({@code com.hogar.<feature>.<capa>...}), salvo {@code shared}. */
    private static Optional<String> featureOf(JavaClass clazz) {
        Matcher m = FEATURE_LAYER.matcher(clazz.getName());
        if (!m.matches() || SHARED.equals(m.group(1))) {
            return Optional.empty();
        }
        return Optional.of(m.group(1));
    }
}
