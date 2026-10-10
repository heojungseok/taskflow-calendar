package com.taskflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.taskflow", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule 웹_계층_밖에서는_웹_계층에_의존하지_않는다 = noClasses()
            .that()
            .resideOutsideOfPackage("com.taskflow..web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.taskflow..web..");

    @ArchTest
    static final ArchRule 도메인은_외부_연동_계층에_의존하지_않는다 = noClasses()
            .that()
            .resideInAPackage("com.taskflow.calendar.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.taskflow.calendar.integration..");

    @ArchTest
    static final ArchRule calendar_하위_패키지_사이에_순환이_없다 =
            slices().matching("com.taskflow.calendar.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule 최상위_패키지_사이에_순환이_없다 =
            slices().matching("com.taskflow.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule identity는_다른_컨텍스트에_의존하지_않는다 = noClasses()
            .that()
            .resideInAPackage("com.taskflow.identity..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.taskflow.calendar..", "com.taskflow.demo..", "com.taskflow.web..");

    @ArchTest
    static final ArchRule 공용_패키지는_컨텍스트에_의존하지_않는다 = noClasses()
            .that()
            .resideInAnyPackage("com.taskflow.common..", "com.taskflow.observability..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.taskflow.identity..", "com.taskflow.calendar..", "com.taskflow.demo..");
}
