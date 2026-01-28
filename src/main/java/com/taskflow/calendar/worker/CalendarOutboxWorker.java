package com.taskflow.calendar.worker;

import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.CalendarOutboxRepository;
import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.integration.googlecalendar.GoogleCalendarService;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import com.taskflow.calendar.integration.googlecalendar.exception.RetryableIntegrationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Calendar Outbox Worker
 * - 5초마다 처리 가능한 Outbox 조회
 * - 조건부 UPDATE로 원자적 선점 (Race Condition 방지)
 * - Lease timeout: 5분
 * - Max retry: 6회
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CalendarOutboxWorker {

    private final CalendarOutboxRepository outboxRepository;
    private final CalendarOutboxService outboxService;
    private final GoogleCalendarService googleCalendarService;

    private static final int MAX_RETRY = 6;
    private static final int LEASE_TIMEOUT_MINUTES = 5;

    @Scheduled(fixedDelay = 15000)  // 15초 설정
    public void pollAndProcess() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime leaseTimeout = now.minusMinutes(LEASE_TIMEOUT_MINUTES);

            log.info("[Worker] Polling at {}, leaseTimeout={}", now, leaseTimeout);

            // 1. 처리 가능한 Outbox 조회
            List<CalendarOutbox> processableOutboxes = outboxRepository.findProcessable(
                    now,
                    leaseTimeout,
                    MAX_RETRY
            );

            if (processableOutboxes.isEmpty()) {
                log.debug("[Worker] No processable outboxes found");
                return;
            }

            log.info("[Worker] Found {} processable outboxes", processableOutboxes.size());

            // 2. 각 Outbox 처리
            for (CalendarOutbox outbox : processableOutboxes) {
                processOne(outbox);
            }
        } catch (Exception e) {
            log.error("[Worker] Fatal error in polling cycle: {}", e.getMessage(), e);
        }
    }

    private void processOne(CalendarOutbox outbox) {
        try {
            // 1. Lock 시도 (조건부 UPDATE로 원자적 선점)
            boolean locked = outboxService.claimProcessing(outbox.getId());
            if (!locked) {
                log.debug("[Worker] Outbox {} already claimed by another worker", outbox.getId());
                return;
            }

            log.info("[Worker] Processing Outbox {} - OpType: {}, TaskId: {}, RetryCount: {}",
                    outbox.getId(), outbox.getOpType(), outbox.getTaskId(), outbox.getRetryCount());

            // 2. Google Calendar API 호출 (내부에서 Task 최신 상태 조회)
            googleCalendarService.handle(outbox);

            // 3. 성공 처리
            outboxService.markSuccess(outbox.getId());
            log.info("[Worker] Successfully processed Outbox {}", outbox.getId());

        } catch (RetryableIntegrationException e) {
            // 재시도 가능한 예외 (네트워크, 5xx 등)
            log.warn("[Worker] Retryable error on Outbox {}: {}",
                    outbox.getId(), e.getMessage());
            outboxService.markForRetry(outbox.getId(), e.getMessage());

        } catch (NonRetryableIntegrationException e) {
            // 재시도 불가능한 예외 (401, 403, 400 등)
            log.error("[Worker] NonRetryable error on Outbox {}: {}",
                    outbox.getId(), e.getMessage());
            outboxService.markFailed(outbox.getId(), e.getMessage());

        } catch (Exception e) {
            // 예상치 못한 예외 → Retryable로 처리
            log.error("[Worker] Unexpected error on Outbox {}: {}",
                    outbox.getId(), e.getMessage(), e);
            outboxService.markForRetry(outbox.getId(),
                    "Unexpected error: " + e.getMessage());
        }
    }
}