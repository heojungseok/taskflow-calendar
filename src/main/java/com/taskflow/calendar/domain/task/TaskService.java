package com.taskflow.calendar.domain.task;

import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.project.exception.ProjectNotFoundException;
import com.taskflow.calendar.domain.task.dto.*;
import com.taskflow.calendar.domain.task.exception.TaskNotFoundException;
import com.taskflow.calendar.domain.user.User;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.calendar.domain.user.exception.UserNotFoundException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.ValidationException;
import com.taskflow.security.SecurityContextHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TaskHistoryRepository historyRepository;
    private final CalendarOutboxService calendarOutboxService;

    /**
     * Task 생성
     */
    @Transactional
    public TaskResponse createTask(Long projectId, CreateTaskRequest request) {
        // 1. 프로젝트 조회
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        // 2. Assignee 조회 (있는 경우)
        User assignee = null;
        if (request.getAssigneeUserId() != null) {
            assignee = userRepository.findById(request.getAssigneeUserId())
                    .orElseThrow(() -> new UserNotFoundException(request.getAssigneeUserId()));
        }

        // 3. 일정 검증
        validateSchedule(request.getStartAt(), request.getDueAt());

        // 4. 캘린더 동기화 검증
        validateCalendarSync(request.getCalendarSyncEnabled(), request.getDueAt());

        // 5. Task 생성
        Task task = Task.createTask(
                project,
                request.getTitle(),
                request.getDescription(),
                assignee,
                request.getStartAt(),
                request.getDueAt(),
                request.getCalendarSyncEnabled()
        );

        // 6. 저장
        Task savedTask = taskRepository.save(task);

        // 7. 이력 기록 - 생성
        recordHistory(
                savedTask,
                TaskChangeType.CONTENT,
                null,  // beforeValue: 생성 시에는 null
                buildTaskSnapshot(savedTask),  // afterValue: 생성된 Task 정보
                SecurityContextHelper.getCurrentUserId()
        );

        // Outbox 적재
         if (savedTask.isCalendarSyncActive()) {
             calendarOutboxService.enqueueUpsert(savedTask);
         }

        return TaskResponse.from(savedTask);
    }

    /**
     * Task 수정
     */
    @Transactional
    public TaskResponse updateTask(Long taskId, UpdateTaskRequest request) {
        // 1. Task 조회 (deleted=false)
        Task task = taskRepository.findByIdAndDeletedFalse(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        // 변경 전 스냅샷 저장
        String beforeSnapshot = buildTaskSnapshot(task);

        // 2. Assignee 조회 (변경하는 경우)
        User assignee = null;
        if (request.getAssigneeUserId() != null) {
            assignee = userRepository.findById(request.getAssigneeUserId())
                    .orElseThrow(() -> new UserNotFoundException(request.getAssigneeUserId()));
        }

        // 3. 일정 검증 (startAt 또는 dueAt 변경 시)
        LocalDateTime newStartAt = request.getStartAt() != null ? request.getStartAt() : task.getStartAt();
        LocalDateTime newDueAt = request.getDueAt() != null ? request.getDueAt() : task.getDueAt();
        validateSchedule(newStartAt, newDueAt);

        // 4. 캘린더 동기화 검증 (변경 시)
        Boolean newCalendarSyncEnabled = request.getCalendarSyncEnabled() != null
                ? request.getCalendarSyncEnabled()
                : task.getCalendarSyncEnabled();
        LocalDateTime finalDueAt = request.getDueAt() != null ? request.getDueAt() : task.getDueAt();
        validateCalendarSync(newCalendarSyncEnabled, finalDueAt);

        // 5. Task 업데이트
        task.update(
                request.getTitle(),
                request.getDescription(),
                assignee,
                request.getStartAt(),
                request.getDueAt(),
                request.getCalendarSyncEnabled()
        );

        // 변경 후 스냅샷 저장
        String afterSnapshot = buildTaskSnapshot(task);

        // 6. 이력 기록 - 수정
        recordHistory(
                task,
                TaskChangeType.CONTENT,
                beforeSnapshot,
                afterSnapshot,
                SecurityContextHelper.getCurrentUserId()
        );

        // 7. Outbox 적재
         if (task.isCalendarSyncActive()) {
             calendarOutboxService.enqueueUpsert(task);
         } else if (task.getCalendarEventId() != null && !task.isCalendarSyncActive()) {
             // 동기화 비활성화 시 DELETE
             calendarOutboxService.enqueueDelete(task);
         }

        return TaskResponse.from(task);
    }

    /**
     * Task 상태 변경
     */
    @Transactional
    public TaskResponse changeTaskStatus(Long taskId, ChangeTaskStatusRequest request) {
        // 1. Task 조회
        Task task = taskRepository.findByIdAndDeletedFalse(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        // 2. 상태 전이 검증
        TaskStatus oldStatus = task.getStatus();
        TaskStatusPolicy.validateTransition(task.getStatus(), request.getToStatus());

        // 3. 상태 변경
        task.changeStatus(request.getToStatus());

        // 4. 이력 기록 - 상태 변경
        recordHistory(
                task,
                TaskChangeType.STATUS,
                oldStatus.name(),
                request.getToStatus().name(),
                SecurityContextHelper.getCurrentUserId()
        );

        // 4. Outbox 적재
         if (task.isCalendarSyncActive()) {
             calendarOutboxService.enqueueUpsert(task);  // DONE이면 [DONE] prefix 추가
         }

        return TaskResponse.from(task);
    }

    /**
     * Task 조회 (단건)
     */
    public TaskResponse getTask(Long taskId) {
        Task task = taskRepository.findByIdAndDeletedFalse(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        return TaskResponse.from(task);
    }

    /**
     * Task 목록 조회 (프로젝트별)
     */
    public List<TaskResponse> listTasks(Long projectId, TaskStatus status, Long assigneeUserId) {
        List<Task> tasks;

        if (status != null) {
            // 상태 필터링
            tasks = taskRepository.findAllByProjectIdAndStatusAndDeletedFalse(projectId, status);
        } else if (assigneeUserId != null) {
            // 담당자 필터링
            tasks = taskRepository.findAllByAssigneeIdAndDeletedFalse(assigneeUserId);
        } else {
            // 전체 조회
            tasks = taskRepository.findAllByProjectIdAndDeletedFalse(projectId);
        }

        return tasks.stream()
                .map(TaskResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Task 삭제 (Soft Delete)
     */
    @Transactional
    public DeleteTaskResponse deleteTask(Long taskId, Long requestedByUserId) {
        // 1. Task 조회
        Task task = taskRepository.findByIdAndDeletedFalse(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        // 2. 삭제 전 스냅샷 저장 (추가!)
        String beforeSnapshot = buildTaskSnapshot(task);

        // 3. Soft Delete 처리
        task.markAsDeleted();

        // 3. 이력 기록 - 삭제
        recordHistory(
                task,
                TaskChangeType.CONTENT,
                beforeSnapshot,
                "deleted=true",
                requestedByUserId
        );

        // 3. Outbox 적재
        calendarOutboxService.enqueueDelete(task);

        return DeleteTaskResponse.of(taskId);
    }

    /**
     * Task 이력 조회
     */
    public List<TaskHistoryResponse> getTaskHistory(Long taskId) {
        // 1. Task 존재 확인 (deleted=false)
        taskRepository.findByIdAndDeletedFalse(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        // 2. 이력 조회
        List<TaskHistory> histories = historyRepository.findByTask_IdOrderByCreatedAtDesc(taskId);

        // 3. DTO 변환
        return histories.stream()
                .map(TaskHistoryResponse::from)
                .collect(Collectors.toList());
    }

    // ========== Private 검증 메서드들 ==========

    /**
     * 일정 검증: startAt <= dueAt
     */
    private void validateSchedule(LocalDateTime startAt, LocalDateTime dueAt) {
        if (startAt != null && dueAt != null && startAt.isAfter(dueAt)) {
            throw new ValidationException(ErrorCode.SCHEDULE_INVALID);
        }
    }

    /**
     * 캘린더 동기화 검증: calendarSyncEnabled=true면 dueAt 필수
     */
    private void validateCalendarSync(Boolean calendarSyncEnabled, LocalDateTime dueAt) {
        if (Boolean.TRUE.equals(calendarSyncEnabled) && dueAt == null) {
            throw new ValidationException(ErrorCode.CALENDAR_SYNC_REQUIRES_DUE_AT);
        }
    }

    private void recordHistory(Task task, TaskChangeType changeType, String beforeValue, String afterValue, Long changedByUserId) {
        User user = userRepository.findById(changedByUserId)
                .orElseThrow(() -> new UserNotFoundException(changedByUserId));

        TaskHistory history = TaskHistory.builder()
                .task(task)
                .changedByUser(user)
                .changeType(changeType)
                .beforeValue(beforeValue)
                .afterValue(afterValue)
                .build();

        historyRepository.save(history);
    }

    /**
     * Task의 현재 상태를 문자열 스냅샷으로 생성
     */
    private String buildTaskSnapshot(Task task) {
        return String.format(
                "title=%s, description=%s, status=%s, assigneeUserId=%s, startAt=%s, dueAt=%s, calendarSyncEnabled=%s",
                task.getTitle(),
                task.getDescription() != null ? task.getDescription() : "null",
                task.getStatus().name(),
                task.getAssignee() != null ? task.getAssignee().getId() : "null",
                task.getStartAt() != null ? task.getStartAt().toString() : "null",
                task.getDueAt() != null ? task.getDueAt().toString() : "null",
                task.getCalendarSyncEnabled()
        );
    }
}