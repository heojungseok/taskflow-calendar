package com.taskflow.common;

public enum ErrorCode {

    // Validation
    VALIDATION_ERROR("VALIDATION_ERROR", "Invalid input"),

    // Business rules
    SCHEDULE_INVALID("SCHEDULE_INVALID", "Start time must be before due time"),
    CALENDAR_SYNC_REQUIRES_DUE_AT("CALENDAR_SYNC_REQUIRES_DUE_AT", "Calendar sync requires due date"),
    TASK_STATUS_TRANSITION_NOT_ALLOWED("TASK_STATUS_TRANSITION_NOT_ALLOWED", "Status transition not allowed"),
    DUPLICATE_EMAIL("DUPLICATE_EMAIL", "Duplicate email"),

    // Not found
    TASK_NOT_FOUND("TASK_NOT_FOUND", "Task not found"),
    PROJECT_NOT_FOUND("PROJECT_NOT_FOUND", "Project not found"),
    USER_NOT_FOUND("USER_NOT_FOUND", "User not found"),

    // OAuth
    EMAIL_NOT_VERIFIED("EMAIL_NOT_VERIFIED", "Google 이메일 인증이 필요합니다"),
    INVALID_OAUTH_STATE("INVALID_OAUTH_STATE", "OAuth 상태가 유효하지 않습니다"),

    // Generic
    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", "Internal server error"),
    UNAUTHORIZED("UNAUTHORIZED", "Unauthorized");

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
