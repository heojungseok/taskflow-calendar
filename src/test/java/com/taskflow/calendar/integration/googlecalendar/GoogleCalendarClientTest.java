package com.taskflow.calendar.integration.googlecalendar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;

/**
 * 실제 구글 Calendar API를 호출하고 실제 캘린더에 이벤트를 쓴다.
 *
 * <p>살아있는 refresh token이 있어야 통과한다. OAuth 동의 화면의 게시 상태가
 * Testing이면 토큰이 7일마다 만료되므로 상시 실행 대상이 아니다.
 * 돌리려면 구글 재로그인 후 {@code GOOGLE_CALENDAR_LIVE_TEST_ENABLED=true}로 실행한다.
 * (SummaryDecodingExperimentTest와 같은 관례다.)
 *
 * <p>스케줄러가 켜진 채 컨텍스트가 뜨면 테스트 도중 워커가 실제 구글 API를 호출한다.
 */
@SpringBootTest(properties = "outbox.worker.enabled=false")
class GoogleCalendarClientTest {

    @Autowired
    private GoogleCalendarClient client;

    @Test
    void contextLoads() {
        // Spring Context가 로드되는지만 확인
        assertThat(client).isNotNull();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GOOGLE_CALENDAR_LIVE_TEST_ENABLED", matches = "(?i)true")
    void createAndUpdateEvent_통합테스트() {
        Long userId = 4L;

        // 1. Create
        CalendarEventDto createEvent = CalendarEventDto.builder()
                .title("통합 테스트 이벤트")
                .description("생성 후 바로 수정")
                .startAt(LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0))
                .endAt(LocalDateTime.now().plusDays(1).withHour(15).withMinute(0).withSecond(0).withNano(0))
                .build();

        String eventId = client.createEvent(userId, createEvent);
        assertThat(eventId).isNotNull();
        System.out.println("✅ Created Event ID: " + eventId);

        // 2. Update (생성한 eventId 사용)
        CalendarEventDto updateEvent = CalendarEventDto.builder()
                .title("[수정됨] 통합 테스트")
                .description("제목 변경 완료")
                .startAt(LocalDateTime.now().plusDays(2).withHour(16).withMinute(0).withSecond(0).withNano(0))
                .endAt(LocalDateTime.now().plusDays(2).withHour(17).withMinute(30).withSecond(0).withNano(0))
                .build();

        client.updateEvent(userId, eventId, updateEvent);
        System.out.println("✅ Event updated: " + eventId);

        // 3. Delete (정리)
        client.deleteEvent(userId, eventId);
        System.out.println("✅ Event deleted: " + eventId);
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "GOOGLE_CALENDAR_LIVE_TEST_ENABLED", matches = "(?i)true")
    void updateEvent_멱등성_같은요청_여러번() {
        Long userId = 4L;

        // 1. Create
        CalendarEventDto createEvent = CalendarEventDto.builder()
                .title("멱등성 테스트 이벤트")
                .description("update 멱등성")
                .startAt(LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0))
                .endAt(LocalDateTime.now().plusDays(1).withHour(11).withMinute(0).withSecond(0).withNano(0))
                .build();

        String eventId = client.createEvent(userId, createEvent);
        assertThat(eventId).isNotNull();

        // 2. Update 요청 (동일 payload)
        CalendarEventDto updateEvent = CalendarEventDto.builder()
                .title("[수정] 멱등성 테스트")
                .description("같은 update를 여러 번")
                .startAt(LocalDateTime.now().plusDays(2).withHour(14).withMinute(0).withSecond(0).withNano(0))
                .endAt(LocalDateTime.now().plusDays(2).withHour(15).withMinute(0).withSecond(0).withNano(0))
                .build();

        // 3. 동일 update를 2번 호출
        client.updateEvent(userId, eventId, updateEvent);
        client.updateEvent(userId, eventId, updateEvent);

        // 4. 예외 없이 여기까지 왔으면 멱등성 OK
        System.out.println("✅ Update idempotency verified for eventId=" + eventId);

        // 5. Cleanup
        client.deleteEvent(userId, eventId);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GOOGLE_CALENDAR_LIVE_TEST_ENABLED", matches = "(?i)true")
    void deleteEvent_멱등성_중복호출() {
        Long userId = 4L;

        // 1. Create
        CalendarEventDto createEvent = CalendarEventDto.builder()
                .title("DELETE 멱등성 테스트")
                .description("delete 중복 호출")
                .startAt(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0))
                .endAt(LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0))
                .build();

        String eventId = client.createEvent(userId, createEvent);
        assertThat(eventId).isNotNull();

        // 2. 첫 번째 delete
        client.deleteEvent(userId, eventId);

        // 3. 동일 delete를 한 번 더 호출
        //    → Google Calendar API 기준: 이미 삭제된 경우 404가 날 수 있음
        //    → Client에서 404를 "성공(no-op)"으로 처리해야 멱등성
        assertThatCode(() -> client.deleteEvent(userId, eventId))
                .doesNotThrowAnyException();

        System.out.println("✅ Delete idempotency verified for eventId=" + eventId);
    }
}