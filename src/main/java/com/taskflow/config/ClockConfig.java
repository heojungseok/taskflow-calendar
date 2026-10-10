package com.taskflow.config;

import com.taskflow.common.TimeZones;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** "오늘"·"이번 주" 판정 기준 시계(KST). 테스트는 고정 시계로 바꿔 날짜 경계를 검증한다. */
    @Bean
    public Clock serviceClock() {
        return Clock.system(TimeZones.SERVICE_ZONE);
    }
}
