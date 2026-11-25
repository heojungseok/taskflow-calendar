package com.taskflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestJvmTimezoneTest {

    @Test
    @DisplayName("테스트 JVM 기본 시간대가 Gradle 속성 testJvmTimezone으로 넘긴 값과 같다")
    void jvmDefaultTimezoneMatchesRequested() {
        String requested = System.getProperty("taskflow.test.requestedJvmTimezone", "UTC");

        assertEquals(requested, TimeZone.getDefault().getID());
    }
}
