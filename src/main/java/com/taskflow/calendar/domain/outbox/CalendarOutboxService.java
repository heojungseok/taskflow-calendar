package com.taskflow.calendar.domain.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.security.SecurityContextHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class CalendarOutboxService {

    private final CalendarOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    // 1. UPSERT 적재
    @Transactional
    public void enqueueUpsert(Task task) {
        // 1. 삭제 로직
        int deleted = outboxRepository.deleteByTaskIdAndStatusAndOpType(task.getId(), OutboxStatus.PENDING, OutboxOpType.UPSERT);
        if (deleted > 0) {
            log.debug("Task {} - Coalescing: {}개 PENDING UPSERT 제거", task.getId(), deleted);
        }
        // 2. payload 작성
        String payload = buildUpsertPayload(task);

        // 3. Outbox 저장
        CalendarOutbox outbox = CalendarOutbox.forUpsert(task.getId(), payload);

        outboxRepository.save(outbox);

    }

    // 2. DELETE 적재
    @Transactional
    public void enqueueDelete(Task task) {
        // 1.PENDING UPSERT 전체 삭제
        int deletedUpserts = outboxRepository.deleteByTaskIdAndStatusAndOpType(task.getId(), OutboxStatus.PENDING, OutboxOpType.UPSERT);
        if (deletedUpserts > 0) {
            log.debug("Task {} - DELETE 적재 시 {}개 PENDING UPSERT 제거", task.getId(), deletedUpserts);
        }
        // 2. PENDING DELETE 확인
        boolean exists = outboxRepository.existsByTaskIdAndStatusAndOpType(task.getId(), OutboxStatus.PENDING, OutboxOpType.DELETE);

        if (exists) {
            return; // 있다면 skip
        }

        // 3. Payload 생성
        String payload = buildDeletePayload(task);

        // 5. DELETE 저장
        CalendarOutbox outbox = CalendarOutbox.forDelete(task.getId(), payload);

        outboxRepository.save(outbox);

    }

    // 3. 상태 변경
    @Transactional
    public void markSuccess(Long outboxId) {
        CalendarOutbox outbox = outboxRepository.findById(outboxId)
                .orElseThrow(()-> new IllegalArgumentException("Outbox not found: " + outboxId));

        outbox.markAsSuccess();
    }

    @Transactional
    public void markForRetry(Long outboxId, String errorMessage) {
        CalendarOutbox outbox = outboxRepository.findById(outboxId)
                .orElseThrow(()-> new IllegalArgumentException("Outbox not found: " + outboxId));

        // Backoff 계산
        LocalDateTime nextRetry = calculateNextRetry(outbox.getRetryCount());

        outbox.markForRetry(errorMessage, nextRetry);
    }

    @Transactional
    public void markFailed(Long outboxId, String errorMessage) {
        CalendarOutbox outbox = outboxRepository.findById(outboxId)
                .orElseThrow(()-> new IllegalArgumentException("Outbox not found: " + outboxId));

        outbox.markAsFailed(errorMessage);
    }

    @Transactional
    public boolean claimProcessing(Long outboxId, LocalDateTime leaseTimeout) {
        int updated = outboxRepository.claimForProcessing(outboxId, leaseTimeout);
        return updated == 1;  // ✅ 선점 성공 여부 명확!
    }

    /**
     * Outbox 목록 조회 (필터링)
     */
    public List<CalendarOutbox> listOutboxes(OutboxStatus status, Long taskId) {
        if (taskId != null) {
            return outboxRepository.findAllByTaskIdOrderByCreatedAtDesc(taskId);
        } else if (status != null) {
            return outboxRepository.findAllByStatusOrderByCreatedAtDesc(status);
        } else {
            return outboxRepository.findTop100ByOrderByCreatedAtDesc();
        }
    }

    /**
     * Outbox 단건 조회
     */
    public CalendarOutbox getOutbox(Long outboxId) {
        return outboxRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox not found: " + outboxId));
    }

    /**
     * Outbox payload에서 userId 추출
     * payload 구조: { "meta": { "requestedByUserId": 1 } }
     */
    public Long extractUserIdFromPayload(CalendarOutbox outbox) {
        try {
            Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), Map.class);
            Map<String, Object> meta = (Map<String, Object>) payload.get("meta");
            Number userId = (Number) meta.get("requestedByUserId");
            return userId.longValue();
        } catch (Exception e) {
            throw new IllegalStateException("Outbox payload에서 userId 추출 실패. outboxId=" + outbox.getId(), e);
        }
    }

    private String buildUpsertPayload(Task task) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("version", 1);
        payload.put("taskId", task.getId());
        payload.put("opType", "UPSERT");

        Map<String, Object> event = new HashMap<>();
        event.put("eventId", task.getCalendarEventId());
        event.put("title", formatTitle(task));
        event.put("description", task.getDescription());
        event.put("startAt", task.getDueAt().minusHours(1).toString());
        event.put("endAt", task.getDueAt().toString());
        payload.put("event", event);

        Map<String, Object> meta = new HashMap<>();
        meta.put("requestedAt", LocalDateTime.now().toString());
        meta.put("requestedByUserId", SecurityContextHelper.getCurrentUserId());
        payload.put("meta", meta);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Payload 생성 실패", e);
        }
    }

    private String buildDeletePayload(Task task) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("version", 1);
        payload.put("taskId", task.getId());
        payload.put("opType", "DELETE");

        Map<String, Object> event = new HashMap<>();
        event.put("eventId", task.getCalendarEventId());  // eventId만!
        payload.put("event", event);

        // meta는 UPSERT와 동일
        Map<String, Object> meta = new HashMap<>();
        meta.put("requestedAt", LocalDateTime.now().toString());
        meta.put("requestedByUserId", SecurityContextHelper.getCurrentUserId());
        payload.put("meta", meta);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Payload 생성 실패", e);
        }
    }

    private String formatTitle(Task task) {
        // DONE 상태면 [DONE] prefix
        if (TaskStatus.DONE.equals(task.getStatus())) {
            return "[DONE] " + task.getTitle();
        }
        return task.getTitle();
    }

    private LocalDateTime calculateNextRetry(int retryCount) {
        LocalDateTime now = LocalDateTime.now();

        switch (retryCount) {
            case 0: return now.plusMinutes(1);
            case 1: return now.plusMinutes(5);
            case 2: return now.plusMinutes(15);
            case 3: return now.plusHours(1);
            case 4: return now.plusHours(6);
            case 5: return now.plusHours(24);
            default: // maxRetry 초과
                return null;
        }
    }
}
