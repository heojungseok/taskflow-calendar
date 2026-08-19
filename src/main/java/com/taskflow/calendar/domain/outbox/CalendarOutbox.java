package com.taskflow.calendar.domain.outbox;

import com.taskflow.calendar.domain.outbox.exception.InvalidOutboxStateTransitionException;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "calendar_outbox",
        indexes = {
                @Index(name = "idx_outbox_status_next_retry", columnList = "status, next_retry_at"),
                @Index(name = "idx_outbox_task_created", columnList = "task_id, created_at"),
                @Index(name = "idx_outbox_task_status_optype", columnList = "task_id, status, op_type")
        }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalendarOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "op_type", nullable = false, length = 10)
    private OutboxOpType opType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at",nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private CalendarOutbox(Long taskId, OutboxOpType opType, String payload,
                           OutboxStatus status, int retryCount,
                           LocalDateTime nextRetryAt, String lastError) {
        this.taskId = taskId;
        this.opType = opType;
        this.payload = payload;
        this.status = status != null ? status : OutboxStatus.PENDING;
        this.retryCount = retryCount;
        this.nextRetryAt = nextRetryAt;
        this.lastError = lastError;
    }

    // Static factory methods
    public static CalendarOutbox forUpsert(Long taskId, String payload) {
        return CalendarOutbox.builder()
                .taskId(taskId)
                .opType(OutboxOpType.UPSERT)
                .payload(payload)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }

    public static CalendarOutbox forDelete(Long taskId, String payload) {
        return CalendarOutbox.builder()
                .taskId(taskId)
                .opType(OutboxOpType.DELETE)
                .payload(payload)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }

    // 상태 변경 메서드들
    public void markAsProcessing() {
        this.status = OutboxStatus.PROCESSING;
    }

    /**
     * SUCCESS 마킹 (상태 전이 검증 포함)
     */
    public void markAsSuccess() {
        validateCurrentlyProcessing();

        this.status = OutboxStatus.SUCCESS;
        this.lastError = null;
        this.nextRetryAt = null;
    }

    /**
     * 재시도 마킹 (상태 전이 검증 포함)
     */
    public void markForRetry(String errorMessage, LocalDateTime nextRetry) {
        validateCurrentlyProcessing();

        this.status = OutboxStatus.FAILED;
        this.retryCount++;
        this.lastError = errorMessage;
        this.nextRetryAt = nextRetry;
    }

    /**
     * 실패 마킹
     */
    public void markAsFailed(String errorMessage) {
        validateCurrentlyProcessing();

        this.status = OutboxStatus.FAILED;
        this.lastError = errorMessage;
        this.nextRetryAt = null;
    }

    /**
     * 건너뜀 마킹. 구글 연동이 없어 호출 자체를 하지 않은 경우다.
     * FAILED와 달리 조치가 필요 없고 재시도 대상도 아니다 - findProcessable이 집지 않는다.
     */
    public void markAsSkipped(String reason) {
        validateCurrentlyProcessing();

        this.status = OutboxStatus.SKIPPED;
        this.lastError = reason;
        this.nextRetryAt = null;
    }

    private void validateCurrentlyProcessing() {
        if (this.status != OutboxStatus.PROCESSING) {
            throw new InvalidOutboxStateTransitionException(
                    String.format("Cannot transition from %s (expected: PROCESSING)", this.status)
            );
        }
    }
}

