package com.taskflow.web;

import com.taskflow.calendar.domain.task.TaskService;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.calendar.domain.task.dto.*;
import com.taskflow.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class TaskController {

    private final TaskService taskService;

    /**
     * Task 생성
     * POST /api/projects/{projectId}/tasks
     */
    @PostMapping("/projects/{projectId}/tasks")
    public ApiResponse<TaskResponse> createTask(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateTaskRequest request) {

        TaskResponse response = taskService.createTask(projectId, request);
        return ApiResponse.success(response);
    }

    /**
     * Task 목록 조회 (프로젝트별)
     * GET /api/projects/{projectId}/tasks?status=IN_PROGRESS&assigneeUserId=1
     */
    @GetMapping("/projects/{projectId}/tasks")
    public ApiResponse<List<TaskResponse>> listTasks(
            @PathVariable Long projectId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) Long assigneeUserId) {

        List<TaskResponse> responses = taskService.listTasks(projectId, status, assigneeUserId);
        return ApiResponse.success(responses);
    }

    /**
     * Task 조회 (단건)
     * GET /api/tasks/{taskId}
     */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskResponse> getTask(@PathVariable Long taskId) {
        TaskResponse response = taskService.getTask(taskId);
        return ApiResponse.success(response);
    }

    /**
     * Task 수정
     * PATCH /api/tasks/{taskId}
     */
    @PatchMapping("/tasks/{taskId}")
    public ApiResponse<TaskResponse> updateTask(
            @PathVariable Long taskId,
            @Valid @RequestBody UpdateTaskRequest request) {

        TaskResponse response = taskService.updateTask(taskId, request);
        return ApiResponse.success(response);
    }

    /**
     * Task 상태 변경
     * POST /api/tasks/{taskId}/status
     */
    @PostMapping("/tasks/{taskId}/status")
    public ApiResponse<TaskResponse> changeTaskStatus(
            @PathVariable Long taskId,
            @Valid @RequestBody ChangeTaskStatusRequest request) {

        TaskResponse response = taskService.changeTaskStatus(taskId, request);
        return ApiResponse.success(response);
    }

    /**
     * Task 삭제 (Soft Delete)
     * DELETE /api/tasks/{taskId}
     */
    @DeleteMapping("/tasks/{taskId}")
    public ApiResponse<DeleteTaskResponse> deleteTask(@PathVariable Long taskId, @RequestParam Long requestedByUserId) {
        DeleteTaskResponse response = taskService.deleteTask(taskId, requestedByUserId);
        return ApiResponse.success(response);
    }

    @GetMapping("/tasks/{taskId}/history")
    public ApiResponse<List<TaskHistoryResponse>> getTaskHistory(@PathVariable Long taskId) {
        List<TaskHistoryResponse> responses = taskService.getTaskHistory(taskId);
        return ApiResponse.success(responses);
    }
}