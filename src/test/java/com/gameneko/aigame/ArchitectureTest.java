package com.gameneko.aigame;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 验证 API、共用模型、应用和叙事与具体引擎保持解耦。 */
@AnalyzeClasses(packages = "com.gameneko.aigame", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule api_must_not_depend_on_implementation_details = noClasses()
            .that().resideInAnyPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage("..rulesets..", "..persistence..", "..infrastructure..");

    @ArchTest
    static final ArchRule shared_layers_must_not_depend_on_specific_rulesets = noClasses()
            .that().resideInAnyPackage("..content..", "..engine..", "..application..", "..narrative..")
            .should().dependOnClassesThat().resideInAnyPackage("..rulesets..");

    @ArchTest
    static final ArchRule engine_and_rulesets_must_not_depend_on_frameworks_or_outer_layers = noClasses()
            .that().resideInAnyPackage("..engine..", "..rulesets..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "..api..", "..application..", "..persistence..", "..infrastructure..");

    @ArchTest
    static final ArchRule controllers_must_live_in_api = classes()
            .that().haveSimpleNameEndingWith("Controller")
            .should().resideInAnyPackage("..api..");
}
