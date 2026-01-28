package com.taskflow.calendar.integration.googlecalendar;

import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import com.taskflow.calendar.integration.googlecalendar.exception.RetryableIntegrationException;

/**
 * Google Calendar API 연동 서비스 인터페이스
 * - Worker가 Outbox를 처리할 때 호출
 * - 멱등성 보장 (eventId 기반)
 */
public interface GoogleCalendarService {

    /**
     * Outbox를 처리하여 Google Calendar API 호출
     *
     * @param outbox 처리할 Outbox
     * @throws RetryableIntegrationException 재시도 가능한 예외
     * @throws NonRetryableIntegrationException 재시도 불가능한 예외 (즉시 실패)
     */
    void handle(CalendarOutbox outbox)
            throws RetryableIntegrationException, NonRetryableIntegrationException;
}