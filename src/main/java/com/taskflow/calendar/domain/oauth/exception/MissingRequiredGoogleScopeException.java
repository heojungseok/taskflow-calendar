package com.taskflow.calendar.domain.oauth.exception;

public class MissingRequiredGoogleScopeException extends RuntimeException {
    public MissingRequiredGoogleScopeException(String scope) {
        super("Required Google scope was not granted: " + scope);
    }
}
