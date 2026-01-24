package com.taskflow.calendar.domain.outbox;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED
}
