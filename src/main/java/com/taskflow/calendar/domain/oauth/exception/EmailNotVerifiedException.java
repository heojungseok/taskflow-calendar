package com.taskflow.calendar.domain.oauth.exception;

/**
 * Google 이메일 인증 실패 예외
 */
public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException() {
        super("Email not verified by Google");
    }

    public EmailNotVerifiedException(String message) {
        super(message);
    }
}
