package com.taskflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.taskflow", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule 도메인과_서비스는_웹_계층에_의존하지_않는다 = noClasses()
            .that()
            .resideInAnyPackage("com.taskflow.calendar.domain..", "com.taskflow.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.taskflow.web..");

    @ArchTest
    static final ArchRule 도메인은_외부_연동_계층에_의존하지_않는다 = noClasses()
            .that()
            .resideInAPackage("com.taskflow.calendar.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.taskflow.calendar.integration..");
}
