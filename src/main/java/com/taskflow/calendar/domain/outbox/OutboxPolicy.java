package com.taskflow.calendar.domain.outbox;

public enum OutboxPolicy {
    MAX_RETRY(6),
    LEASE_TIMEOUT_MINUTES(5);

    private final int value;
    OutboxPolicy(int value) { this.value = value; }
    public int value() { return value; }
}
