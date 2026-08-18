package com.taskflow.web;

import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.domain.outbox.dto.OutboxResponse;
import com.taskflow.calendar.worker.CalendarOutboxWorker;
import com.taskflow.common.ApiResponse;
import com.taskflow.security.SecurityContextHelper;
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

        List<CalendarOutbox> outboxes = outboxService.listOutboxes(
                SecurityContextHelper.getCurrentUserId(), status, taskId);

        List<OutboxResponse> responses = outboxes.stream()
                .map(OutboxResponse::from)
                .collect(Collectors.toList());

        return ApiResponse.success(responses);
    }

    // 숫자로 제한한다. 제한이 없으면 /trigger-worker 가 여기 매칭돼 Long 변환에 실패하고 500이 난다.
    @GetMapping("/{outboxId:\\d+}")
    public ApiResponse<OutboxResponse> getOutbox(@PathVariable Long outboxId) {
        CalendarOutbox outbox = outboxService.getOutbox(
                outboxId, SecurityContextHelper.getCurrentUserId());
        return ApiResponse.success(OutboxResponse.from(outbox));
    }

    // 워커를 실제로 돌리는 부작용이 있다. GET이면 크롤러·프리페치가 실행시킨다.
    @PostMapping("/trigger-worker")
    public ResponseEntity<String> triggerWorker() {
        calendarOutboxWorker.pollAndProcess();
        return ResponseEntity.ok("Worker triggered");
    }
}
