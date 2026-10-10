package com.taskflow.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 시간대 기준.
 *
 * <p>기록용 시각(createdAt, updatedAt, 토큰 만료, Outbox 재시도 등)은 UTC 벽시계로 저장한다. JVM 기본 시간대를 UTC로 고정해 JPA Auditing과
 * DB CURRENT_TIMESTAMP도 같은 기준을 따른다. 사용자가 입력하는 일정(startAt, dueAt)과 "오늘"·"이번 주" 판정은 서비스 시간대를 쓴다.
 */
public final class TimeZones {

    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private TimeZones() {}

    /** UTC로 기록된 LocalDateTime을 응답용 Instant로 바꾼다. 응답 JSON에 Z가 붙어 클라이언트가 시간대를 알 수 있다. */
    public static Instant toUtcInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }
}
