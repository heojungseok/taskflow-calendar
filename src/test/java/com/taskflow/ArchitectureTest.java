package com.taskflow;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.taskflow", importOptions = ImportOption.DoNotIncludeTests.class)
/**
 * 컨텍스트 의존 방향: assistant → calendarsync → planning → identity. demo는 모든 컨텍스트를 정리하는 최상위 모듈이다.
 * common·observability·config는 어떤 컨텍스트에도 의존하지 않는다.
 */
class ArchitectureTest {

    private static final String IDENTITY = "com.taskflow.identity..";
    private static final String PLANNING = "com.taskflow.planning..";
    private static final String CALENDARSYNC = "com.taskflow.calendarsync..";
    private static final String ASSISTANT = "com.taskflow.assistant..";
    private static final String DEMO = "com.taskflow.demo..";

    @ArchTest
    static final ArchRule 웹_계층_밖에서는_웹_계층에_의존하지_않는다 = noClasses()
            .that()
            .resideOutsideOfPackage("com.taskflow..web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.taskflow..web..");

    @ArchTest
    static final ArchRule 외부_연동_계층은_calendarsync_밖과_outbox에서_쓰지_않는다 = noClasses()
            .that()
            .resideInAnyPackage(IDENTITY, PLANNING, ASSISTANT, "com.taskflow.calendarsync.outbox..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.taskflow.calendarsync.integration..");

    @ArchTest
    static final ArchRule calendarsync_하위_패키지_사이에_순환이_없다 =
            slices().matching("com.taskflow.calendarsync.(*)..").should().beFreeOfCycles();

    // TODO(3단계): planning → calendarsync·assistant 의존을 끊고 아래 예외를 지운다.
    @ArchTest
    static final ArchRule 최상위_패키지_사이에_순환이_없다 = slices().matching("com.taskflow.(*)..")
            .should()
            .beFreeOfCycles()
            .ignoreDependency(resideInAPackage(PLANNING), resideInAnyPackage(CALENDARSYNC, ASSISTANT));

    @ArchTest
    static final ArchRule identity는_다른_컨텍스트에_의존하지_않는다 = noClasses()
            .that()
            .resideInAPackage(IDENTITY)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(PLANNING, CALENDARSYNC, ASSISTANT, DEMO);

    @ArchTest
    static final ArchRule calendarsync는_assistant와_demo에_의존하지_않는다 = noClasses()
            .that()
            .resideInAPackage(CALENDARSYNC)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(ASSISTANT, DEMO);

    @ArchTest
    static final ArchRule 컨텍스트는_demo에_의존하지_않는다 = noClasses()
            .that()
            .resideInAnyPackage(IDENTITY, PLANNING, CALENDARSYNC, ASSISTANT)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(DEMO);

    @ArchTest
    static final ArchRule 공용_패키지는_컨텍스트에_의존하지_않는다 = noClasses()
            .that()
            .resideInAnyPackage("com.taskflow.common..", "com.taskflow.observability..", "com.taskflow.config..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(IDENTITY, PLANNING, CALENDARSYNC, ASSISTANT, DEMO);
}
