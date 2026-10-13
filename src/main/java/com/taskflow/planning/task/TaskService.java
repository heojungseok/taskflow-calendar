package com.taskflow.planning.task;

import com.taskflow.identity.security.SecurityContextHelper;
import com.taskflow.identity.user.DemoUsageService;
import com.taskflow.identity.user.User;
import com.taskflow.identity.user.UserRepository;
import com.taskflow.identity.user.exception.UserNotFoundException;
import com.taskflow.observability.TaskFlowMetrics;
import com.taskflow.planning.project.Project;
import com.taskflow.planning.project.ProjectRepository;
import com.taskflow.planning.project.exception.ProjectNotFoundException;
import com.taskflow.planning.task.dto.*;
import com.taskflow.planning.task.exception.TaskNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskService {

    private static final int DEMO_TASK_LIMIT = 100;

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final DemoUsageService demoUsageService;
    private final TaskHistoryRepository historyRepository;
    private final TaskSyncStateReader taskSyncStateReader;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskFlowMetrics metrics;

    /**
     * Task 생성
     */
    @Transactional
    public TaskResponse createTask(Long projectId, CreateTaskRequest request) {
        Long userId = SecurityContextHelper.getCurrentUserId();
        // 1. 프로젝트 조회
        Project project = projectRepository
                .findByIdAndOwnerUserId(projectId, userId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        // 삭제된 Task도 센다. 지우고 다시 만드는 방식으로 한도를 피하지 못하게 한다.
        boolean demoUser = demoUsageService.beforeCreate(
                userId, () -> taskRepository.countByProject_OwnerUserId(userId), DEMO_TASK_LIMIT);

        // 2. Assignee 조회 (있는 경우)
        User assignee = null;
        if (request.getAssigneeUserId() != null) {
            assignee = findOwnedAssignee(request.getAssigneeUserId(), userId);
        }

        LocalDateTime normalizedStartAt = TaskScheduleRules.normalizeSyncStartAt(
                request.getCalendarSyncEnabled(), request.getStartAt(), request.getDueAt());

        // 3. 일정 검증
        TaskScheduleRules.validateSchedule(normalizedStartAt, request.getDueAt());

        // 4. 캘린더 동기화 검증
        TaskScheduleRules.validateCalendarSync(request.getCalendarSyncEnabled(), request.getDueAt());

        // 5. Task 생성
        Task task = Task.createTask(
                project,
                request.getTitle(),
                request.getDescription(),
                assignee,
                normalizedStartAt,
                request.getDueAt(),
                request.getCalendarSyncEnabled());

        // 6. 저장
        Task savedTask = taskRepository.save(task);
        if (demoUser) {
            metrics.demoTaskCreated();
        }

        // 7. 이력 기록 - 생성
        recordHistory(
                savedTask,
                TaskChangeType.CONTENT,
                null, // beforeValue: 생성 시에는 null
                TaskHistorySnapshot.buildTaskSnapshot(savedTask), // afterValue: 생성된 Task 정보
                userId);

        eventPublisher.publishEvent(new TaskChangedEvent(savedTask, TaskChangedEvent.Type.CREATED));

        return TaskResponse.from(savedTask);
    }

    /**
     * Task 수정
     */
    @Transactional
    public TaskResponse updateTask(Long taskId, UpdateTaskRequest request) {
        Long userId = SecurityContextHelper.getCurrentUserId();

        // 1. Task 조회 (deleted=false)
        Task task = taskRepository
                .findByIdAndDeletedFalseAndProject_OwnerUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        demoUsageService.beforeMutation(userId);

        // 변경 전 스냅샷 저장
        String beforeSnapshot = TaskHistorySnapshot.buildTaskSnapshot(task);

        // 2. Assignee 조회 (변경하는 경우)
        User assignee = null;
        if (request.getAssigneeUserId() != null) {
            assignee = findOwnedAssignee(request.getAssigneeUserId(), userId);
        }

        // 3. 일정 검증 (startAt 또는 dueAt 변경 시)
        LocalDateTime newStartAtRaw = request.getStartAt() != null ? request.getStartAt() : task.getStartAt();
        LocalDateTime newDueAt = request.getDueAt() != null ? request.getDueAt() : task.getDueAt();

        // 4. 캘린더 동기화 검증 (변경 시)
        Boolean newCalendarSyncEnabled = request.getCalendarSyncEnabled() != null
                ? request.getCalendarSyncEnabled()
                : task.getCalendarSyncEnabled();
        LocalDateTime normalizedStartAt =
                TaskScheduleRules.normalizeSyncStartAt(newCalendarSyncEnabled, newStartAtRaw, newDueAt);
        TaskScheduleRules.validateSchedule(normalizedStartAt, newDueAt);

        LocalDateTime finalDueAt = request.getDueAt() != null ? request.getDueAt() : task.getDueAt();
        TaskScheduleRules.validateCalendarSync(newCalendarSyncEnabled, finalDueAt);

        // 5. Task 업데이트
        task.update(
                request.getTitle(),
                request.getDescription(),
                assignee,
                normalizedStartAt,
                request.getDueAt(),
                request.getCalendarSyncEnabled());

        // 변경 후 스냅샷 저장
        String afterSnapshot = TaskHistorySnapshot.buildTaskSnapshot(task);

        // 6. 이력 기록 - 수정
        recordHistory(task, TaskChangeType.CONTENT, beforeSnapshot, afterSnapshot, userId);

        eventPublisher.publishEvent(new TaskChangedEvent(task, TaskChangedEvent.Type.UPDATED));

        return TaskResponse.from(task);
    }

    /**
     * Task 상태 변경
     */
    @Transactional
    public TaskResponse changeTaskStatus(Long taskId, ChangeTaskStatusRequest request) {
        Long userId = SecurityContextHelper.getCurrentUserId();
        // 1. Task 조회
        Task task = taskRepository
                .findByIdAndDeletedFalseAndProject_OwnerUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        demoUsageService.beforeMutation(userId);

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
                userId);

        eventPublisher.publishEvent(new TaskChangedEvent(task, TaskChangedEvent.Type.STATUS_CHANGED));

        return TaskResponse.from(task);
    }

    /**
     * Task 조회 (단건)
     */
    public TaskResponse getTask(Long taskId) {
        Task task = taskRepository
                .findByIdAndDeletedFalseAndProject_OwnerUserId(taskId, SecurityContextHelper.getCurrentUserId())
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        return TaskResponse.from(task);
    }

    /**
     * Task 목록 조회 (프로젝트별)
     */
    public List<TaskResponse> listTasks(Long projectId, TaskStatus status, Long assigneeUserId) {
        List<Task> tasks;

        Long ownerUserId = SecurityContextHelper.getCurrentUserId();

        // 두 필터는 함께 올 수 있다. else-if로 묶으면 담당자 조건이 조용히 무시된다.
        if (status != null && assigneeUserId != null) {
            tasks = taskRepository.findAllByProjectIdAndStatusAndAssigneeIdAndDeletedFalseAndProject_OwnerUserId(
                    projectId, status, assigneeUserId, ownerUserId);
        } else if (status != null) {
            // 상태 필터링
            tasks = taskRepository.findAllByProjectIdAndStatusAndDeletedFalseAndProject_OwnerUserId(
                    projectId, status, ownerUserId);
        } else if (assigneeUserId != null) {
            // 담당자 필터링 (프로젝트 안에서만)
            tasks = taskRepository.findAllByProjectIdAndAssigneeIdAndDeletedFalseAndProject_OwnerUserId(
                    projectId, assigneeUserId, ownerUserId);
        } else {
            // 전체 조회
            tasks = taskRepository.findAllByProjectIdAndDeletedFalseAndProject_OwnerUserId(projectId, ownerUserId);
        }

        // Outbox는 건당이 아니라 한 번에 읽는다. 건당이면 목록 크기만큼 쿼리가 나간다.
        Map<Long, TaskSyncState> syncStates = taskSyncStateReader.readAll(tasks);
        return tasks.stream()
                .map(task -> TaskResponse.from(task, syncStates.get(task.getId())))
                .collect(Collectors.toList());
    }

    /**
     * Task 삭제 (Soft Delete)
     */
    @Transactional
    public DeleteTaskResponse deleteTask(Long taskId, Long requestedByUserId) {
        // 1. Task 조회
        Task task = taskRepository
                .findByIdAndDeletedFalseAndProject_OwnerUserId(taskId, SecurityContextHelper.getCurrentUserId())
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        demoUsageService.beforeMutation(requestedByUserId);

        // 2. 삭제 전 스냅샷 저장 (추가!)
        String beforeSnapshot = TaskHistorySnapshot.buildTaskSnapshot(task);

        // 3. Soft Delete 처리
        task.markAsDeleted();

        // 3. 이력 기록 - 삭제
        recordHistory(task, TaskChangeType.CONTENT, beforeSnapshot, "deleted=true", requestedByUserId);

        eventPublisher.publishEvent(new TaskChangedEvent(task, TaskChangedEvent.Type.DELETED));

        return DeleteTaskResponse.of(taskId);
    }

    /**
     * Task 이력 조회
     */
    public List<TaskHistoryResponse> getTaskHistory(Long taskId) {
        // 1. Task 존재 확인 (deleted=false)
        if (taskRepository
                .findByIdAndDeletedFalseAndProject_OwnerUserId(taskId, SecurityContextHelper.getCurrentUserId())
                .isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }

        // 2. 이력 조회
        List<TaskHistory> histories = historyRepository.findByTask_IdOrderByCreatedAtDesc(taskId);

        // 3. DTO 변환
        return histories.stream().map(TaskHistoryResponse::from).collect(Collectors.toList());
    }

    // ========== Private 검증 메서드들 ==========

    private void recordHistory(
            Task task, TaskChangeType changeType, String beforeValue, String afterValue, Long changedByUserId) {
        User user =
                userRepository.findById(changedByUserId).orElseThrow(() -> new UserNotFoundException(changedByUserId));

        TaskHistory history = TaskHistory.builder()
                .task(task)
                .changedByUser(user)
                .changeType(changeType)
                .beforeValue(beforeValue)
                .afterValue(afterValue)
                .build();

        historyRepository.save(history);
    }

    private User findOwnedAssignee(Long assigneeUserId, Long ownerUserId) {
        if (!ownerUserId.equals(assigneeUserId)) {
            throw new UserNotFoundException(assigneeUserId);
        }
        return userRepository.findById(assigneeUserId).orElseThrow(() -> new UserNotFoundException(assigneeUserId));
    }
}
