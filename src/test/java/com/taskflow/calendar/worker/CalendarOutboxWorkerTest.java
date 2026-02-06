package com.taskflow.calendar.worker;

import com.taskflow.calendar.domain.oauth.GoogleOAuthService;
import com.taskflow.calendar.domain.outbox.*;
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

import java.time.LocalDateTime;
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
            when(outboxRepository.findProcessable(any(), any(), anyInt()))
                    .thenReturn(List.of(outbox));
            when(outboxService.claimProcessing(eq(OUTBOX_ID), any())).thenReturn(true);
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
            when(outboxRepository.findProcessable(any(), any(), anyInt()))
                    .thenReturn(List.of(outbox));
            when(outboxService.claimProcessing(eq(OUTBOX_ID), any())).thenReturn(true);
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
            when(outboxRepository.findProcessable(any(), any(), anyInt()))
                    .thenReturn(List.of(outbox));
            when(outboxService.claimProcessing(eq(OUTBOX_ID), any())).thenReturn(true);
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
            when(outboxRepository.findProcessable(any(), any(), anyInt()))
                    .thenReturn(List.of(outbox));
            when(outboxService.claimProcessing(eq(OUTBOX_ID), any())).thenReturn(true);
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

    // =========================================================
    // Lease Timeout & Race Condition 테스트
    // =========================================================
    @Nested
    @DisplayName("Lease Timeout & 동시성 검증")
    class LeaseTimeoutAndRaceConditionTest {

        @Test
        @DisplayName("Lease timeout 초과 후 재선점 가능")
        void lease_timeout_후_재선점() {
            // given: PROCESSING 상태이나 updatedAt이 lease timeout 초과
            CalendarOutbox staleOutbox = CalendarOutbox.builder()
                    .taskId(TASK_ID)
                    .opType(OutboxOpType.UPSERT)
                    .payload(VALID_PAYLOAD)
                    .status(OutboxStatus.PROCESSING)
                    .retryCount(0)
                    .build();

            // Reflection으로 updatedAt을 10분 전으로 설정 (lease timeout = 5분)
            try {
                var field = CalendarOutbox.class.getDeclaredField("updatedAt");
                field.setAccessible(true);
                field.set(staleOutbox, LocalDateTime.now().minusMinutes(10));

                var idField = CalendarOutbox.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(staleOutbox, OUTBOX_ID);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // findProcessable이 stale outbox 반환
            when(outboxRepository.findProcessable(any(), any(), anyInt()))
                    .thenReturn(List.of(staleOutbox));

            // claimProcessing 성공 (lease timeout 체크 통과)
            when(outboxService.claimProcessing(eq(OUTBOX_ID), any())).thenReturn(true);

            // when
            worker.pollAndProcess();

            // then: 재선점 후 처리됨
            verify(outboxService).claimProcessing(eq(OUTBOX_ID), any());
            verify(googleCalendarService).handle(staleOutbox);
            verify(outboxService).markSuccess(OUTBOX_ID);
        }

        @Test
        @DisplayName("claimProcessing 실패 시 처리 skip")
        void claimProcessing_실패_skip() {
            // given: 다른 Worker가 이미 선점
            when(outboxRepository.findProcessable(any(), any(), anyInt()))
                    .thenReturn(List.of(outbox));
            when(outboxService.claimProcessing(eq(OUTBOX_ID), any())).thenReturn(false);

            // when
            worker.pollAndProcess();

            // then: GoogleCalendarService 호출 안 됨
            verify(outboxService).claimProcessing(eq(OUTBOX_ID), any());
            verify(googleCalendarService, never()).handle(any());
            verify(outboxService, never()).markSuccess(anyLong());
            verify(outboxService, never()).markFailed(anyLong(), anyString());
        }

        @Test
        @DisplayName("PROCESSING 상태에서 다른 Worker 선점 불가 (updatedAt 최신)")
        void PROCESSING_상태_선점불가() {
            // given: PROCESSING 상태이며 updatedAt이 최신 (lease timeout 미초과)
            CalendarOutbox processingOutbox = CalendarOutbox.builder()
                    .taskId(TASK_ID)
                    .opType(OutboxOpType.UPSERT)
                    .payload(VALID_PAYLOAD)
                    .status(OutboxStatus.PROCESSING)
                    .retryCount(0)
                    .build();

            // updatedAt을 1분 전으로 설정 (lease timeout 5분 미초과)
            try {
                var field = CalendarOutbox.class.getDeclaredField("updatedAt");
                field.setAccessible(true);
                field.set(processingOutbox, LocalDateTime.now().minusMinutes(1));

                var idField = CalendarOutbox.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(processingOutbox, OUTBOX_ID);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // findProcessable이 비어있음 (lease timeout 미초과로 필터링됨)
            when(outboxRepository.findProcessable(any(), any(), anyInt()))
                    .thenReturn(List.of()); // 빈 리스트

            // when
            worker.pollAndProcess();

            // then: 처리 안 됨
            verify(outboxService, never()).claimProcessing(anyLong(), any());
            verify(googleCalendarService, never()).handle(any());
        }
    }
}