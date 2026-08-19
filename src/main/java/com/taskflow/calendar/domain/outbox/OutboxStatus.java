package com.taskflow.calendar.domain.outbox;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    /** 구글 연동이 없는 사용자의 요청. 구글을 호출하지 않고 종결한다. 재시도 대상이 아니다. */
    SKIPPED
}
