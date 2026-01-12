package com.taskflow.calendar.domain.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    // Soft Delete 고려 - 단건 조회
    Optional<Task> findByIdAndDeletedFalse(Long id);

    // 프로젝트별 Task 목록
    List<Task> findAllByProjectIdAndDeletedFalse(Long projectId);

    // 프로젝트 + 상태별 필터링
    List<Task> findAllByProjectIdAndStatusAndDeletedFalse(Long projectId, TaskStatus status);

    // 담당자별 Task 목록
    List<Task> findAllByAssigneeIdAndDeletedFalse(Long assigneeId);

    // 전체 Task 목록 (삭제되지 않은 것만)
    List<Task> findAllByDeletedFalse();
}