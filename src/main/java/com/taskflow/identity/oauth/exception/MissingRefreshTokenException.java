package com.taskflow.identity.oauth.exception;

public class MissingRefreshTokenException extends RuntimeException {
    public MissingRefreshTokenException() {
        super("Google refresh token was not issued");
    }
}
