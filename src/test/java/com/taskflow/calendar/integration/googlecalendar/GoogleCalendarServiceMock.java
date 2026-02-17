package com.taskflow.calendar.integration.googlecalendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.OutboxOpType;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import com.taskflow.calendar.integration.googlecalendar.exception.RetryableIntegrationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Random;

// @Service  // ← Mock 비활성화: 실제 GoogleCalendarServiceImpl 사용
@RequiredArgsConstructor
@Slf4j
public class GoogleCalendarServiceMock implements GoogleCalendarService {

    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    @Transactional
    @Override
    public void handle(CalendarOutbox outbox) {

        log.info("[MOCK] Processing Outbox {} - OpType: {}, TaskId: {}",
                outbox.getId(), outbox.getOpType(), outbox.getTaskId());

        // 10% 확률로 NonRetryable 예외 테스트
        if (random.nextInt(100) < 10) {
            log.error("[MOCK] Simulating NonRetryable error (401 Unauthorized)");
            throw new NonRetryableIntegrationException("Mock 401 Unauthorized - Token expired", 0);
        }

        // 5% 확률로 Retryable 예외 테스트
        if (random.nextInt(100) < 5) {
            log.error("[MOCK] Simulating Retryable error (503 Service Unavailable)");
            throw new RetryableIntegrationException("Mock 503 Service Unavailable");
        }

        try {
            // 1. Payload 파싱
            Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), Map.class);

            Long taskId = ((Number) payload.get("taskId")).longValue();

            // 2. DELETE payload 기반 처리
            if (OutboxOpType.DELETE.equals(outbox.getOpType())) {
                handleDelete(payload);
                log.info("[MOCK] Successfully processed DELETE Outbox {}", outbox.getId());
                return;
            }

            // 2. Source of Truth: Task UPSERT만 최신 상태 조회
            Task task = taskRepository.findByIdAndDeletedFalse(taskId).orElse(null);

            // Task 삭제되었거나 동기화 비활성화된 경우 → skip (성공 처리)
            if (task == null || !task.isCalendarSyncActive()) {
                log.info("[MOCK] Task {} - Skipped (deleted or sync disabled)", taskId);
                return;
            }

            handleUpsert(task, payload);
            log.info("[MOCK] Successfully processed UPSERT Outbox {}", outbox.getId());

        } catch (NonRetryableIntegrationException | RetryableIntegrationException e) {
            throw e;  // 그대로 재던짐
        } catch (Exception e) {
            log.error("[MOCK] Unexpected error processing Outbox {}: {}",
                    outbox.getId(), e.getMessage());
            throw new RetryableIntegrationException(
                    "Mock processing failed: " + e.getMessage(), e
            );
        }
    }

    @SuppressWarnings("unchecked")
    private void handleUpsert(Task task, Map<String, Object> payload) {
        // payload.event는 디버깅용으로만 사용 (null-safe)
        Map<String, Object> event = (Map<String, Object>) payload.get("event");
        String payloadTitle = event == null ? null : (String) event.get("title");

        // ✅ Source of Truth: Task 기준으로 판단/표현
        String eventId = task.getCalendarEventId();
        String title = task.getTitle(); // (DONE prefix 등은 실제 구현에서 Task→Event 매핑 단계에서 반영)

        if (eventId != null) {
            // UPDATE (멱등)
            log.info("[MOCK] UPDATE Event - TaskId: {}, EventId: {}, Title(Task): {}, Title(Payload): {}",
                    task.getId(), eventId, title, payloadTitle);
        } else {
            // CREATE (첫 생성)
            String mockEventId = "mock-event-" + task.getId() + "-" + System.currentTimeMillis();
            log.info("[MOCK] CREATE Event - TaskId: {}, MockEventId: {}, Title(Task): {}, Title(Payload): {}",
                    task.getId(), mockEventId, title, payloadTitle);

            task.setCalendarEventId(mockEventId);
            taskRepository.save(task);
        }
    }

    // Payload 기반 처리 (Task 파라미터 제거)
    @SuppressWarnings("unchecked")
    private void handleDelete(Map<String, Object> payload) {
        Map<String,Object> event = (Map<String,Object>) payload.get("event");
        String eventId = event == null ? null : (String) event.get("eventId");
        Long taskId = ((Number) payload.get("taskId")).longValue();

        if (eventId == null) {
            // eventId 없으면 no-op (명세 준수)
            log.info("[MOCK] DELETE no-op - Task {} has no eventId", taskId);
            return;
        }

        // 외부 API 호출 (Mock)
        log.info("[MOCK] DELETE Event - TaskId: {}, EventId: {}", taskId, eventId);
    }
}
