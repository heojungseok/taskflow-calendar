package com.taskflow.calendar.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CalendarOutboxRepository extends JpaRepository<CalendarOutbox, Long> {
    // 1. 정적 Coalescing용 DELETE
    // 힌트: @Modifying, @Query 필요
    @Modifying
    @Query("DELETE FROM CalendarOutbox " +
            "where taskId = :taskId " +
            "AND status = :status " +
            "AND opType = :opType")
    int deleteByTaskIdAndStatusAndOpType(@Param("taskId") Long taskId, @Param("status") OutboxStatus status, @Param("opType") OutboxOpType opType);

    // 2. PENDING DELETE 존재 여부 체크
    // 힌트: existsBy... 메서드명으로 자동 생성 가능
    boolean existsByTaskIdAndStatusAndOpType(Long taskId, OutboxStatus status, OutboxOpType opType);

    // 3. Worker용 처리 가능한 Outbox 조회
    // 힌트: 복잡한 조건이므로 @Query 필수
    @Query("SELECT o " +
            "FROM CalendarOutbox o " +
            "WHERE (" +
            "    (o.status IN ('PENDING', 'FAILED') AND (o.nextRetryAt IS NULL OR o.nextRetryAt <= :now))" +
            "    OR " +
            "    (o.status = 'PROCESSING' AND o.updatedAt < :leaseTimeout)" +
            ")" +
            "AND o.retryCount < :maxRetry " +
            "ORDER BY o.createdAt ASC")
    List<CalendarOutbox> findProcessable(@Param("now") LocalDateTime now, @Param("leaseTimeout") LocalDateTime leaseTimeout, @Param("maxRetry") int maxRetry);

    // 조건부 UPDATE 추가
    @Modifying
    @Query("UPDATE CalendarOutbox o " +
            "SET o.status = 'PROCESSING', o.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE o.id = :id " +
            "AND (o.status IN ('PENDING', 'FAILED')" +
            "   OR " +
            "   (o.status = 'PROCESSING' AND o.updatedAt < :leaseTimeout) " +
            ")")
    int claimForProcessing(@Param("id") Long id, @Param("leaseTimeout") LocalDateTime leaseTimeout);

    // 4. Task별 Outbox 이력 조회 (선택)
    // 힌트: findAllBy... 메서드명으로 가능
    List<CalendarOutbox> findAllByTaskIdOrderByCreatedAtDesc(Long taskId);

    /**
     * Task ID + 상태별 Outbox 조회 (최신순)
     */
    List<CalendarOutbox> findAllByTaskIdAndStatusOrderByCreatedAtDesc(Long taskId, OutboxStatus status);

    /**
     * 상태별 Outbox 조회 (최신순)
     * - 관측 API용
     */
    List<CalendarOutbox> findAllByStatusOrderByCreatedAtDesc(OutboxStatus status);

    /**
     * 전체 Outbox 조회 (최신순, 최대 100개)
     * - 관측 API용 (페이징 없이 최근 100개만)
     */
    List<CalendarOutbox> findTop100ByOrderByCreatedAtDesc();

    /**
     * Task별 최신 Outbox 1개 조회
     * - 캘린더 동기화 상태 API용
     */
    Optional<CalendarOutbox> findTopByTaskIdOrderByCreatedAtDesc(Long taskId);

    /**
     * Task별 마지막 SUCCESS Outbox 조회
     * - 마지막 동기화 성공 시각 제공
     */
    Optional<CalendarOutbox> findTopByTaskIdAndStatusOrderByUpdatedAtDesc(Long taskId, OutboxStatus status);
}
