package com.taskflow.assistant.llm;

import java.io.IOException;

final class GeminiHttpException extends IOException {

    private final int statusCode;
    private final transient String retryAfter;
    private final transient String body;

    GeminiHttpException(int statusCode, String retryAfter, String body) {
        super("Gemini responded with HTTP " + statusCode);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
        this.body = body;
    }

    int statusCode() {
        return statusCode;
    }

    String retryAfter() {
        return retryAfter;
    }

    String body() {
        return body;
    }
}
