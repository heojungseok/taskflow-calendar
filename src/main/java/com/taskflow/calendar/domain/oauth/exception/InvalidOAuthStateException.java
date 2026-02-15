package com.taskflow.calendar.domain.oauth.exception;

/**
 * OAuth State 검증 실패 예외 (CSRF 방어)
 */
public class InvalidOAuthStateException extends RuntimeException {
    public InvalidOAuthStateException() {
        super("Invalid or expired OAuth state");
    }

    public InvalidOAuthStateException(String message) {
        super(message);
    }
}
