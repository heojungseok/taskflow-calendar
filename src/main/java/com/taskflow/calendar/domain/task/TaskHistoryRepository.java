package com.taskflow.calendar.domain.task;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskHistoryRepository extends JpaRepository<TaskHistory, Long> {

    @EntityGraph(attributePaths = {"changedByUser"})
    List<TaskHistory> findByTask_IdOrderByCreatedAtDesc(Long taskId);

    @Modifying
    @Query(value = """
            DELETE FROM task_history
            WHERE task_id IN (
              SELECT t.id FROM tasks t JOIN projects p ON p.id = t.project_id
              WHERE p.owner_user_id = :userId
            )
            """, nativeQuery = true)
    int deleteOwnedBy(@Param("userId") Long userId);
}
