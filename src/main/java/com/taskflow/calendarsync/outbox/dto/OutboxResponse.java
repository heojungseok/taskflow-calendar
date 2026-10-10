package com.taskflow.calendarsync.outbox.dto;

import com.taskflow.calendarsync.outbox.CalendarOutbox;
import com.taskflow.calendarsync.outbox.OutboxOpType;
import com.taskflow.calendarsync.outbox.OutboxStatus;
import com.taskflow.common.TimeZones;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

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
    private Instant nextRetryAt;
    private String lastError;
    private String payload;
    private Instant createdAt;
    private Instant updatedAt;

    public static OutboxResponse from(CalendarOutbox outbox) {
        return OutboxResponse.builder()
                .id(outbox.getId())
                .taskId(outbox.getTaskId())
                .opType(outbox.getOpType())
                .status(outbox.getStatus())
                .retryCount(outbox.getRetryCount())
                .nextRetryAt(TimeZones.toUtcInstant(outbox.getNextRetryAt()))
                .lastError(outbox.getLastError())
                .payload(outbox.getPayload())
                .createdAt(TimeZones.toUtcInstant(outbox.getCreatedAt()))
                .updatedAt(TimeZones.toUtcInstant(outbox.getUpdatedAt()))
                .build();
    }
}
