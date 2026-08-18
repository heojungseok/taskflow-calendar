package com.taskflow.calendar.domain.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    // Soft Delete 고려 - 단건 조회
    Optional<Task> findByIdAndDeletedFalse(Long id);

    // ── 소유자 스코프 조회 ────────────────────────────────
    // 격리 단위는 프로젝트다. 검사를 빠뜨리면 결과가 비는 쪽(fail-closed)으로 떨어진다.

    Optional<Task> findByIdAndDeletedFalseAndProject_OwnerUserId(Long id, Long ownerUserId);

    List<Task> findAllByProjectIdAndDeletedFalseAndProject_OwnerUserId(Long projectId, Long ownerUserId);

    List<Task> findAllByProjectIdAndStatusAndDeletedFalseAndProject_OwnerUserId(
            Long projectId, TaskStatus status, Long ownerUserId);

    List<Task> findAllByAssigneeIdAndDeletedFalseAndProject_OwnerUserId(Long assigneeId, Long ownerUserId);

    List<Task> findAllByDeletedFalseAndProject_OwnerUserId(Long ownerUserId);

    // 프로젝트별 Task 목록
    List<Task> findAllByProjectIdAndDeletedFalse(Long projectId);

    // 프로젝트 + 상태별 필터링
    List<Task> findAllByProjectIdAndStatusAndDeletedFalse(Long projectId, TaskStatus status);

    // 담당자별 Task 목록
    List<Task> findAllByAssigneeIdAndDeletedFalse(Long assigneeId);

    // 전체 Task 목록 (삭제되지 않은 것만)
    List<Task> findAllByDeletedFalse();
}