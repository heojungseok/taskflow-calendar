package com.taskflow.calendar.domain.outbox.dto;

import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.OutboxOpType;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Outbox 조회 응답 DTO
 * - 관측/디버깅용
 * - payload 포함 (문제 진단 용이)
 */
@Getter
@Builder
public class OutboxResponse {

    private Long id;
    private Long taskId;
    private OutboxOpType opType;
    private OutboxStatus status;
    private int retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private String payload;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static OutboxResponse from (CalendarOutbox outbox) {
        return OutboxResponse.builder()
                .id(outbox.getId())
                .taskId(outbox.getTaskId())
                .opType(outbox.getOpType())
                .status(outbox.getStatus())
                .retryCount(outbox.getRetryCount())
                .nextRetryAt(outbox.getNextRetryAt())
                .lastError(outbox.getLastError())
                .payload(outbox.getPayload())
                .createdAt(outbox.getCreatedAt())
                .updatedAt(outbox.getUpdatedAt())
                .build();
    }
}
