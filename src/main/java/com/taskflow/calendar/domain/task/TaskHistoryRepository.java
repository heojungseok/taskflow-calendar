package com.taskflow.calendar.domain.task;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskHistoryRepository extends JpaRepository<TaskHistory, Long> {

    @EntityGraph(attributePaths = {"changedByUser"})
    List<TaskHistory> findByTask_IdOrderByCreatedAtDesc(Long taskId);
}