package com.taskflow.common;

public enum ErrorCode {

    // Validation
    VALIDATION_ERROR("VALIDATION_ERROR", "Invalid input"),

    // Business rules
    SCHEDULE_INVALID("SCHEDULE_INVALID", "Start time must be before due time"),
    CALENDAR_SYNC_REQUIRES_DUE_AT("CALENDAR_SYNC_REQUIRES_DUE_AT", "Calendar sync requires due date"),
    TASK_STATUS_TRANSITION_NOT_ALLOWED("TASK_STATUS_TRANSITION_NOT_ALLOWED", "Status transition not allowed"),

    // Not found
    TASK_NOT_FOUND("TASK_NOT_FOUND", "Task not found"),
    PROJECT_NOT_FOUND("PROJECT_NOT_FOUND", "Project not found"),
    USER_NOT_FOUND("USER_NOT_FOUND", "User not found"),

    // Generic
    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", "Internal server error");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
