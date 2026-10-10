package com.taskflow;

import java.time.ZoneOffset;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TaskFlowCalendarApplication {

    public static void main(String[] args) {
        // 기록용 시각은 UTC 기준이다. JPA Auditing과 DB CURRENT_TIMESTAMP도 JVM 기본 시간대를 따르므로 실행 환경과 무관하게 고정한다.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
        SpringApplication.run(TaskFlowCalendarApplication.class, args);
    }
}
