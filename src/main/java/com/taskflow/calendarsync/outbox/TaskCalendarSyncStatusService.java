package com.taskflow.calendarsync.outbox;

import com.taskflow.calendarsync.outbox.dto.CalendarSyncStatusResponse;
import com.taskflow.identity.security.SecurityContextHelper;
import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskRepository;
import com.taskflow.planning.task.exception.TaskNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskCalendarSyncStatusService {

    private final TaskRepository taskRepository;
    private final CalendarOutboxService calendarOutboxService;

    /** 현재 사용자 소유 Task의 캘린더 동기화 설정과 최신·마지막 성공 Outbox. */
    public CalendarSyncStatusResponse getStatus(Long taskId) {
        Task task = taskRepository
                .findByIdAndDeletedFalseAndProject_OwnerUserId(taskId, SecurityContextHelper.getCurrentUserId())
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        return CalendarSyncStatusResponse.of(
                task,
                calendarOutboxService.findLatestByTaskId(taskId).orElse(null),
                calendarOutboxService.findLastSuccessByTaskId(taskId).orElse(null));
    }
}
