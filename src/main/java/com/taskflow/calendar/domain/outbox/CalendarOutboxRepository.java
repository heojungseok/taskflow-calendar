package com.taskflow.calendar.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
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
            "    o.status = 'PENDING'" +
            "    OR " +
            "    (o.status = 'FAILED' AND o.nextRetryAt IS NOT NULL AND o.nextRetryAt <= :now)" +
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
            "AND (o.status = 'PENDING'" +
            "   OR " +
            "   (o.status = 'FAILED' AND o.nextRetryAt IS NOT NULL AND o.nextRetryAt <= :now)" +
            "   OR " +
            "   (o.status = 'PROCESSING' AND o.updatedAt < :leaseTimeout) " +
            ")")
    int claimForProcessing(@Param("id") Long id,
                           @Param("now") LocalDateTime now,
                           @Param("leaseTimeout") LocalDateTime leaseTimeout);

    /**
     * 소유자 기준 Outbox 조회 (최신순). status/taskId는 선택 필터다.
     * 소유는 payload의 meta.requestedByUserId로 판단한다 — Task.assignee는 91%가 비어 있어 쓸 수 없다.
     * ponytail: payload를 jsonb로 캐스팅해 매번 스캔한다. 현재 수백 건이라 문제없다.
     *   건수가 커지면 calendar_outbox에 user_id 컬럼을 두고 인덱스를 건다.
     */
    @Query(value = """
            SELECT * FROM calendar_outbox o
            WHERE o.payload::jsonb -> 'meta' ->> 'requestedByUserId' = cast(:userId AS text)
              AND (cast(:status AS text) IS NULL OR o.status = cast(:status AS text))
              AND (cast(:taskId AS bigint) IS NULL OR o.task_id = cast(:taskId AS bigint))
            ORDER BY o.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<CalendarOutbox> findOwnedBy(@Param("userId") Long userId,
                                     @Param("status") String status,
                                     @Param("taskId") Long taskId,
                                     @Param("limit") int limit);

    /**
     * 소유자 기준 Outbox 단건 조회. 남의 것이면 empty다.
     */
    @Query(value = """
            SELECT * FROM calendar_outbox o
            WHERE o.id = :outboxId
              AND o.payload::jsonb -> 'meta' ->> 'requestedByUserId' = cast(:userId AS text)
            """, nativeQuery = true)
    Optional<CalendarOutbox> findOwnedById(@Param("outboxId") Long outboxId,
                                           @Param("userId") Long userId);

    /**
     * Task별 최신 Outbox 1개 조회
     * - 캘린더 동기화 상태 API용
     */
    Optional<CalendarOutbox> findTopByTaskIdOrderByCreatedAtDesc(Long taskId);

    /**
     * Task 묶음의 최신 Outbox를 taskId당 1건씩 조회한다.
     * 목록 화면·검색·추천이 Task마다 findTopByTaskId...를 부르면 그대로 N+1이다.
     *
     * created_at은 같은 초에 여러 건이 들어갈 수 있어 id를 tie-breaker로 둔다.
     * 단건 조회(findTopByTaskIdOrderByCreatedAtDesc)와 결과가 갈리지 않게 하려는 것이다.
     */
    @Query(value = """
            SELECT DISTINCT ON (o.task_id) *
            FROM calendar_outbox o
            WHERE o.task_id IN (:taskIds)
            ORDER BY o.task_id, o.created_at DESC, o.id DESC
            """, nativeQuery = true)
    List<CalendarOutbox> findLatestByTaskIdIn(@Param("taskIds") Collection<Long> taskIds);

    /**
     * Task별 마지막 SUCCESS Outbox 조회
     * - 마지막 동기화 성공 시각 제공
     */
    Optional<CalendarOutbox> findTopByTaskIdAndStatusOrderByUpdatedAtDesc(Long taskId, OutboxStatus status);

    @Query(value = """
            SELECT EXISTS (
              SELECT 1 FROM calendar_outbox o
              JOIN tasks t ON t.id = o.task_id
              JOIN projects p ON p.id = t.project_id
              WHERE p.owner_user_id = :userId AND o.status = 'PROCESSING'
            )
            """, nativeQuery = true)
    boolean existsProcessingForOwner(@Param("userId") Long userId);

    @Query(value = """
            SELECT o.* FROM calendar_outbox o
            JOIN tasks t ON t.id = o.task_id
            JOIN projects p ON p.id = t.project_id
            WHERE p.owner_user_id = :userId
            FOR UPDATE OF o
            """, nativeQuery = true)
    List<CalendarOutbox> lockOwnedBy(@Param("userId") Long userId);

    @Modifying
    @Query(value = """
            DELETE FROM calendar_outbox
            WHERE task_id IN (
              SELECT t.id FROM tasks t JOIN projects p ON p.id = t.project_id
              WHERE p.owner_user_id = :userId
            )
            """, nativeQuery = true)
    int deleteOwnedBy(@Param("userId") Long userId);
}
