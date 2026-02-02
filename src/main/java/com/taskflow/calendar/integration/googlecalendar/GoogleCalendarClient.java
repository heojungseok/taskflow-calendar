package com.taskflow.calendar.integration.googlecalendar;

/**
 * Google Calendar API 클라이언트 인터페이스
 */
public interface GoogleCalendarClient {

    /**
     * 캘린더 이벤트 생성
     *
     * @param userId 사용자 ID (Token 조회용)
     * @param event 생성할 이벤트 정보
     * @return Google Calendar Event ID
     */
    String createEvent(Long userId, CalendarEventDto event);

    /**
     * 캘린더 이벤트 수정
     *
     * @param userId 사용자 ID
     * @param eventId Google Calendar Event ID
     * @param event 수정할 이벤트 정보
     */
    void updateEvent(Long userId, String eventId, CalendarEventDto event);

    /**
     * 캘린더 이벤트 삭제
     *
     * @param userId 사용자 ID
     * @param eventId Google Calendar Event ID
     */
    void deleteEvent(Long userId, String eventId);
}