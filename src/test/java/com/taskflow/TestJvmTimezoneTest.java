package com.taskflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 시간대 조합 검증이 넘긴 JVM 시간대가 다른 설정에 덮이거나 잘못된 ID로 GMT가 되지 않았는지 확인한다. */
class TestJvmTimezoneTest {

    @Test
    @DisplayName("테스트 JVM 기본 시간대가 Gradle 속성 testJvmTimezone으로 넘긴 값과 같다")
    void jvmDefaultTimezoneMatchesRequested() {
        String requested = System.getProperty("taskflow.test.requestedJvmTimezone", "UTC");

        assertEquals(requested, TimeZone.getDefault().getID());
    }
}
