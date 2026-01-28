package com.taskflow.calendar.integration.googlecalendar.exception;

/**
 * 재시도 불가능한 외부 연동 예외 (즉시 실패)
 * - 400 Bad Request (payload 문제)
 * - 401 Unauthorized (토큰 만료)
 * - 403 Forbidden (권한 없음)
 * - 영구적 설정 문제
 */
public class NonRetryableIntegrationException extends RuntimeException {

    public NonRetryableIntegrationException(String message) {super(message);}

    public NonRetryableIntegrationException(String message, Throwable cause) {super(message, cause);}
}