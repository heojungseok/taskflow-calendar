package com.taskflow.calendar.worker;

import com.taskflow.calendar.domain.oauth.GoogleOAuthService;
import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.CalendarOutboxRepository;
import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.domain.outbox.OutboxOpType;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.integration.googlecalendar.GoogleCalendarService;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalendarOutboxWorkerTest {

    @Mock
    private CalendarOutboxRepository outboxRepository;

    @Mock
    private CalendarOutboxService outboxService;

    @Mock
    private GoogleCalendarService googleCalendarService;

    @Mock
    private GoogleOAuthService googleOAuthService;

    @InjectMocks
    private CalendarOutboxWorker worker;

    private static final Long OUTBOX_ID = 100L;
    private static final Long TASK_ID = 1L;
    private static final Long USER_ID = 4L;

    private static final String VALID_PAYLOAD = new StringBuilder()
            .append("{\n")
            .append("  \"version\": 1,\n")
            .append("  \"taskId\": 1,\n")
            .append("  \"opType\": \"UPSERT\",\n")
            .append("  \"event\": {\n")
            .append("    \"eventId\": \"abc123\",\n")
            .append("    \"title\": \"Test Task\",\n")
            .append("    \"startAt\": \"2026-02-01T11:00:00\",\n")
            .append("    \"endAt\": \"2026-02-01T12:00:00\"\n")
            .append("  },\n")
            .append("  \"meta\": {\n")
            .append("    \"requestedAt\": \"2026-02-01T10:00:00\",\n")
            .append("    \"requestedByUserId\": 4\n")
            .append("  }\n")
            .append("}")
            .toString();

    private CalendarOutbox outbox;

    @BeforeEach
    void setUp() {
        // 엔티티 생성
        outbox = CalendarOutbox.builder()
                .taskId(TASK_ID)
                .opType(OutboxOpType.UPSERT)
                .payload(VALID_PAYLOAD)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();

        // id는 @Builder로 세팅 불가 → reflection 사용
        try {
            var field = CalendarOutbox.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(outbox, OUTBOX_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // findProcessable → outbox 반환 (공통)
        when(outboxRepository.findProcessable(any(), any(), anyInt()))
                .thenReturn(List.of(outbox));

        // claimProcessing → 항상 성공 (공통)
        when(outboxService.claimProcessing(OUTBOX_ID)).thenReturn(true);
    }

    // =========================================================
    // 401 흐름 테스트
    // =========================================================
    @Nested
    @DisplayName("401 발생 시 토큰 갱신 흐름")
    class When401Occurs {

        @Test
        @DisplayName("토큰 갱신 성공 시 markForRetry 호출됨")
        void 토큰갱신_성공_markForRetry호출() {
            // given
            doThrow(new NonRetryableIntegrationException("Unauthorized", 401))
                    .when(googleCalendarService).handle(outbox);
            when(outboxService.extractUserIdFromPayload(outbox))
                    .thenReturn(USER_ID);

            // when
            worker.pollAndProcess();

            // then
            verify(googleOAuthService).refreshAccessToken(USER_ID);
            verify(outboxService).markForRetry(OUTBOX_ID, "Token refreshed, will retry");
            verify(outboxService, never()).markFailed(anyLong(), anyString());
        }

        @Test
        @DisplayName("토큰 갱신 실패 시 markFailed 호출됨")
        void 토큰갱신_실패_markFailed호출() {
            // given
            doThrow(new NonRetryableIntegrationException("Unauthorized", 401))
                    .when(googleCalendarService).handle(outbox);
            when(outboxService.extractUserIdFromPayload(outbox))
                    .thenReturn(USER_ID);
            doThrow(new NonRetryableIntegrationException("Refresh token 만료", 400))
                    .when(googleOAuthService).refreshAccessToken(USER_ID);

            // when
            worker.pollAndProcess();

            // then
            verify(googleOAuthService).refreshAccessToken(USER_ID);
            verify(outboxService).markFailed(eq(OUTBOX_ID),
                    argThat(msg -> msg.contains("Token refresh failed")));
            verify(outboxService, never()).markForRetry(anyLong(), anyString());
        }

        @Test
        @DisplayName("payload 파싱 실패 시 markFailed 호출됨")
        void payload파싱_실패_markFailed호출() {
            // given
            doThrow(new NonRetryableIntegrationException("Unauthorized", 401))
                    .when(googleCalendarService).handle(outbox);
            doThrow(new IllegalStateException("userId 추출 실패"))
                    .when(outboxService).extractUserIdFromPayload(outbox);

            // when
            worker.pollAndProcess();

            // then
            verify(googleOAuthService, never()).refreshAccessToken(anyLong());
            verify(outboxService).markFailed(eq(OUTBOX_ID),
                    argThat(msg -> msg.contains("Token refresh failed")));
            verify(outboxService, never()).markForRetry(anyLong(), anyString());
        }
    }

    // =========================================================
    // 400 흐름 테스트
    // =========================================================
    @Nested
    @DisplayName("401 아닌 NonRetryable 발생 시")
    class WhenNon401NonRetryable {

        @Test
        @DisplayName("400 발생 시 토큰 갱신 없이 markFailed 호출됨")
        void statusCode400_갱신없이_markFailed호출() {
            // given
            doThrow(new NonRetryableIntegrationException("Bad Request", 400))
                    .when(googleCalendarService).handle(outbox);

            // when
            worker.pollAndProcess();

            // then
            verify(googleOAuthService, never()).refreshAccessToken(anyLong());
            verify(outboxService, never()).extractUserIdFromPayload(any());
            verify(outboxService).markFailed(OUTBOX_ID, "Bad Request");
            verify(outboxService, never()).markForRetry(anyLong(), anyString());
        }
    }
}