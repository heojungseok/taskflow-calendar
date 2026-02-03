package com.taskflow.calendar.integration.googlecalendar;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.taskflow.calendar.domain.oauth.GoogleOAuthService;
import com.taskflow.calendar.domain.oauth.OAuthGoogleToken;
import com.taskflow.calendar.domain.oauth.OAuthGoogleTokenRepository;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import com.taskflow.calendar.integration.googlecalendar.exception.RetryableIntegrationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleCalendarClientImpl implements GoogleCalendarClient {

    private final OAuthGoogleTokenRepository repository;
    private final GoogleOAuthService googleOAuthService;

    @Override
    public String createEvent(Long userId, CalendarEventDto eventDto) {
        log.info("Creating calendar event. userId={}, title={}", userId, eventDto.getTitle());

        try {
            // Event 객체 생성
            Event event = new Event();
            event.setSummary(eventDto.getTitle());
            event.setDescription(eventDto.getDescription());

            // 시작/종료 시각 설정
            EventDateTime start = new EventDateTime();
            start.setDateTime(toDateTime(eventDto.getStartAt()));
            event.setStart(start);

            EventDateTime end = new EventDateTime();
            end.setDateTime(toDateTime(eventDto.getEndAt()));
            event.setEnd(end);

            return executeWithRetry(userId, () -> {
                // Caledar Service 생성
                Calendar service = getCalendarService(userId);
                // API 호출
                Event created = service.events()
                        .insert("primary", event)
                        .execute();

                // Event ID 반환
                return created.getId();
            });

        } catch (GoogleJsonResponseException e) {
            handleGoogleApiException(e, "createEvent", userId);
            return null;
        }
        catch (IOException e) {
            throw new RetryableIntegrationException("Network error during createEvent");
        }
    }


    @Override
    public void updateEvent(Long userId, String eventId, CalendarEventDto eventDto) {
        log.info("Updating calendar event. userId={}, eventId={}", userId, eventId);

        try {
            executeWithRetry(userId, () -> {
                // Caledar Service 생성
                Calendar service = getCalendarService(userId);

                // 기존 Event 조회
                Event event = service.events().get("primary", eventId).execute();
                // Event 수정
                event.setSummary(eventDto.getTitle());
                event.setDescription(eventDto.getDescription());
                event.setStart(toEventDateTime(eventDto.getStartAt()));
                event.setEnd(toEventDateTime(eventDto.getEndAt()));
                // API 호출
                return service.events().update("primary", eventId, event).execute();
            });

        } catch (GoogleJsonResponseException e) {
            handleGoogleApiException(e, "updateEvent", userId);
        } catch (IOException e) {
            throw new RetryableIntegrationException("Network error during updateEvent", e);
        }
    }

    @Override
    public void deleteEvent(Long userId, String eventId) {
        log.info("Deleting calendar event. userId={}, eventId={}", userId, eventId);

        try {
            executeWithRetry(userId, () -> {
                Calendar service = getCalendarService(userId);
                return service.events().delete("primary", eventId).execute();
            });
        } catch (GoogleJsonResponseException e) {
            handleGoogleApiException(e, "deleteEvent", userId);
        } catch (IOException e) {
            throw new RetryableIntegrationException("Network error", e);
        }

    }

    private Calendar getCalendarService(Long userId) throws IOException {

        // Token 조회
        OAuthGoogleToken token = repository.findByUserId(userId)
                .orElseThrow(() -> new NonRetryableIntegrationException("Token not found. userId=" + userId, 0));
        // Token 만료 확인
        if (token.isExpiringSoon(5)) {
            log.info("Access token expiring soon. Refreshing. userId={}, expiryAt={}",
                    userId, token.getExpiryAt());
            googleOAuthService.refreshAccessToken(userId);
            token = repository.findById(userId).orElseThrow();
        }
        // Credntial 생성
        GoogleCredential credential = new GoogleCredential().setAccessToken(token.getAccessToken());
        // Calendar Service 생성
        Calendar service = new Calendar.Builder(
                new NetHttpTransport(),
                JacksonFactory.getDefaultInstance(),
                credential
        )
                .setApplicationName("TaskFlow Caledar")
                .build();

        log.debug("Calendar service created. userId={}", userId);
        return service;
    }

    /**
     * LocalDateTime → Google DateTime 변환
     */
    private DateTime toDateTime(java.time.LocalDateTime localDateTime) {
        ZonedDateTime zdt = localDateTime.atZone(ZoneId.systemDefault());
        Date date = Date.from(zdt.toInstant());
        return new DateTime(date);
    }

    /**
     * LocalDateTime → EventDateTime 변환
     */
    private EventDateTime toEventDateTime(java.time.LocalDateTime localDateTime) {
        EventDateTime eventDateTime = new EventDateTime();
        eventDateTime.setDateTime(toDateTime(localDateTime));
        return eventDateTime;
    }

    /**
     * Google API 예외 분류
     */
    private void handleGoogleApiException(GoogleJsonResponseException e, String operation, Long userId) {
        int statusCode = e.getStatusCode();
        String reason = e.getDetails() != null ? e.getDetails().getMessage() : "Unknown";

        log.error("Google API error. operation={}, userId={}, status={}, reason={}",
                operation, userId, statusCode, reason);

        if (statusCode == 401 || statusCode == 403) {
            throw new NonRetryableIntegrationException(
                    "Authentication/Authorization failed: " + reason, e.getStatusCode(), e);
        }

        if (statusCode == 429) {
            throw new RetryableIntegrationException("Rate limit exceeded", e);
        }

        if (statusCode >= 500) {
            throw new RetryableIntegrationException("Server error: " + statusCode, e);
        }

        // ✅ 멱등 DELETE 처리
        if (statusCode == 404 || statusCode == 410) {
            log.info("Google Calendar resource already deleted (status={}), treat as success", statusCode);
            return; // ← 여기서 swallow
        }

        throw new NonRetryableIntegrationException(
                "Bad request: " + statusCode + " - " + reason, 0);
    }

    /**
     * Google API 호출을 실행하고, 401 발생 시 토큰 갱신 후 1회 재시도
     *
     * @param userId 토큰 갱신에 필요한 사용자 ID
     * @param call    실행할 Google API 호출 (getCalendarService() 포함)
     * @throws IOException Google API 호출 실패 시
     */
    private <T> T executeWithRetry(Long userId, GoogleApiCall<T> call) throws IOException {
        try {
            return call.execute();

        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 401) {
                log.info("401 Unauthorized. Refreshing token and retrying. userId={}", userId);
                googleOAuthService.refreshAccessToken(userId);
                return call.execute();  // 재시도 1회, 이때 getCalendarService()가 다시 실행됨
            }
            throw e;
        }
    }

    @FunctionalInterface
    private interface GoogleApiCall<T> {
        T execute() throws IOException;
    }
}
