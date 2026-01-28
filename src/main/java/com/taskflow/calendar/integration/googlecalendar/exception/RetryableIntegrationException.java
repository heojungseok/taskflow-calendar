package com.taskflow.calendar.integration.googlecalendar.exception;

/**
 * 재시도 가능한 외부 연동 예외
 * - 네트워크 타임아웃
 * - 5xx 서버 에러
 * - 429 Rate Limit
 * - 일시적 장애
 */
public class RetryableIntegrationException extends RuntimeException {
    public RetryableIntegrationException(String message) {
        super(message);
    }

    public RetryableIntegrationException(String message, Throwable cause) {super(message, cause);}
}
