package com.taskflow.web;

import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.domain.outbox.dto.OutboxResponse;
import com.taskflow.calendar.worker.CalendarOutboxWorker;
import com.taskflow.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/calendar-outbox")
@RequiredArgsConstructor
public class OutboxController {

    private final CalendarOutboxService outboxService;
    private final CalendarOutboxWorker calendarOutboxWorker;

    @GetMapping
    public ApiResponse<List<OutboxResponse>> listOutboxes(
            @RequestParam(required = false) OutboxStatus status,
            @RequestParam(required = false) Long taskId) {

        List<CalendarOutbox> outboxes = outboxService.listOutboxes(status, taskId);

        List<OutboxResponse> responses = outboxes.stream()
                .map(OutboxResponse::from)
                .collect(Collectors.toList());

        return ApiResponse.success(responses);
    }

    @GetMapping("/{outboxId}")
    public ApiResponse<OutboxResponse> getOutbox(@PathVariable Long outboxId) {
        CalendarOutbox outbox = outboxService.getOutbox(outboxId);
        return ApiResponse.success(OutboxResponse.from(outbox));
    }

    @GetMapping("/trigger-worker")
    public ResponseEntity<String> triggerWorker() {
        calendarOutboxWorker.pollAndProcess();
        return ResponseEntity.ok("Worker triggered");
    }
}
