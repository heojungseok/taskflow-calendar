package com.taskflow.calendarsync.web;

import com.taskflow.calendarsync.outbox.TaskCalendarSyncStatusService;
import com.taskflow.calendarsync.outbox.dto.CalendarSyncStatusResponse;
import com.taskflow.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TaskCalendarSyncController {

    private final TaskCalendarSyncStatusService taskCalendarSyncStatusService;

    @GetMapping("/api/tasks/{taskId}/calendar-sync")
    public ApiResponse<CalendarSyncStatusResponse> getCalendarSyncStatus(@PathVariable Long taskId) {
        return ApiResponse.success(taskCalendarSyncStatusService.getStatus(taskId));
    }
}
