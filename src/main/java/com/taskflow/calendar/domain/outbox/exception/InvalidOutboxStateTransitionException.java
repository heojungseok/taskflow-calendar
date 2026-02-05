package com.taskflow.calendar.domain.outbox.exception;

/**
 * Outbox 상태 전이 규칙 위반 예외
 */
public class InvalidOutboxStateTransitionException extends RuntimeException {
    public InvalidOutboxStateTransitionException(String message) {
        super(message);
    }
}
