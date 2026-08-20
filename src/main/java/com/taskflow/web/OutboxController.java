package com.taskflow.web;

import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.domain.outbox.dto.OutboxResponse;
import com.taskflow.common.ApiResponse;
import com.taskflow.security.SecurityContextHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/calendar-outbox")
@RequiredArgsConstructor
public class OutboxController {

    private final CalendarOutboxService outboxService;

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

    // 숫자로 제한해 잘못된 하위 경로를 ID 변환 오류로 만들지 않는다.
    @GetMapping("/{outboxId:\\d+}")
    public ApiResponse<OutboxResponse> getOutbox(@PathVariable Long outboxId) {
        CalendarOutbox outbox = outboxService.getOutbox(
                outboxId, SecurityContextHelper.getCurrentUserId());
        return ApiResponse.success(OutboxResponse.from(outbox));
    }
}
