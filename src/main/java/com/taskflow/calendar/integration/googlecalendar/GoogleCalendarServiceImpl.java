package com.taskflow.calendar.integration.googlecalendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.OutboxOpType;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import com.taskflow.calendar.integration.googlecalendar.exception.RetryableIntegrationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Google Calendar API 실제 연동 구현
 * - Source of Truth: Task의 최신 상태 기준
 * - 멱등성: eventId 기반 create/update 구분
 * - 예외 분류: Retryable(5xx) vs NonRetryable(4xx)
 */
@Service
@Primary
@RequiredArgsConstructor
@Slf4j
public class GoogleCalendarServiceImpl implements GoogleCalendarService {

    private final GoogleCalendarClient googleCalendarClient;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    @Override
    public void handle(CalendarOutbox outbox)
            throws RetryableIntegrationException, NonRetryableIntegrationException {

        log.info("[GoogleCalendarService] Processing Outbox {} - OpType: {}, TaskId: {}",
                outbox.getId(), outbox.getOpType(), outbox.getTaskId());

        try {
            // 1. Payload 파싱 (userId 추출용)
            Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), Map.class);
            Long taskId = ((Number) payload.get("taskId")).longValue();
            
            // meta에서 userId 추출
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) payload.get("meta");
            Long userId = ((Number) meta.get("requestedByUserId")).longValue();

            // 2. OpType별 처리
            if (OutboxOpType.DELETE.equals(outbox.getOpType())) {
                handleDelete(userId, payload);
            } else {
                handleUpsert(userId, taskId);
            }

            log.info("[GoogleCalendarService] Successfully processed Outbox {}", outbox.getId());

        } catch (NonRetryableIntegrationException | RetryableIntegrationException e) {
            throw e;  // 그대로 재던짐
        } catch (Exception e) {
            log.error("[GoogleCalendarService] Unexpected error processing Outbox {}: {}",
                    outbox.getId(), e.getMessage(), e);
            throw new RetryableIntegrationException(
                    "Unexpected error: " + e.getMessage(), e
            );
        }
    }

    /**
     * UPSERT: Task 최신 상태 기준으로 create 또는 update
     */
    private void handleUpsert(Long userId, Long taskId) {
        // Source of Truth: Task 최신 상태 조회
        Task task = taskRepository.findByIdAndDeletedFalse(taskId).orElse(null);

        // Task 삭제되었거나 동기화 비활성화된 경우 → skip
        if (task == null || !task.isCalendarSyncActive()) {
            log.info("[GoogleCalendarService] Task {} - Skipped (deleted or sync disabled)", taskId);
            return;
        }

        // Task → CalendarEventDto 변환
        CalendarEventDto event = buildEventFromTask(task);

        String eventId = task.getCalendarEventId();
        
        if (eventId != null) {
            // UPDATE (멱등)
            log.info("[GoogleCalendarService] Updating event. taskId={}, eventId={}, title={}",
                    taskId, eventId, event.getTitle());
            googleCalendarClient.updateEvent(userId, eventId, event);
        } else {
            // CREATE
            log.info("[GoogleCalendarService] Creating event. taskId={}, title={}",
                    taskId, event.getTitle());
            String newEventId = googleCalendarClient.createEvent(userId, event);
            
            // eventId를 Task에 저장
            task.setCalendarEventId(newEventId);
            taskRepository.save(task);
            
            log.info("[GoogleCalendarService] Event created. taskId={}, eventId={}", taskId, newEventId);
        }
    }

    /**
     * DELETE: Payload의 eventId 기준으로 삭제
     */
    @SuppressWarnings("unchecked")
    private void handleDelete(Long userId, Map<String, Object> payload) {
        Map<String, Object> event = (Map<String, Object>) payload.get("event");
        String eventId = event == null ? null : (String) event.get("eventId");
        Long taskId = ((Number) payload.get("taskId")).longValue();

        if (eventId == null) {
            // eventId 없으면 no-op
            log.info("[GoogleCalendarService] DELETE no-op - Task {} has no eventId", taskId);
            return;
        }

        log.info("[GoogleCalendarService] Deleting event. taskId={}, eventId={}", taskId, eventId);
        googleCalendarClient.deleteEvent(userId, eventId);
    }

    /**
     * Task → CalendarEventDto 변환
     * - DONE 상태면 제목에 [DONE] prefix
     * - 시간: dueAt - 1시간 ~ dueAt
     */
    private CalendarEventDto buildEventFromTask(Task task) {
        String title = task.getTitle();
        
        // DONE 상태면 [DONE] prefix 추가
        if (TaskStatus.DONE.equals(task.getStatus())) {
            title = "[DONE] " + title;
        }

        LocalDateTime endAt = task.getDueAt();
        LocalDateTime startAt = endAt.minusHours(1);

        return CalendarEventDto.builder()
                .title(title)
                .description(task.getDescription())
                .startAt(startAt)
                .endAt(endAt)
                .build();
    }
}
