package com.taskflow.calendar.domain.summary;

import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.domain.outbox.OutboxOpType;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.domain.task.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TaskSyncStateResolver {

    private final CalendarOutboxService calendarOutboxService;

    public SummaryTaskSnapshot resolve(Task task) {
        CalendarOutbox latestOutbox = calendarOutboxService.findLatestByTaskId(task.getId())
                .orElse(null);

        return toSnapshot(task, latestOutbox);
    }

    /**
     * 목록용. Task마다 resolve()를 부르면 Outbox 조회가 건수만큼 나간다(N+1).
     * 최신 Outbox를 한 번에 받아 같은 분류 로직에 태운다.
     */
    public List<SummaryTaskSnapshot> resolveAll(List<Task> tasks) {
        Map<Long, CalendarOutbox> latestByTaskId = calendarOutboxService.findLatestByTaskIds(
                tasks.stream().map(Task::getId).toList());

        return tasks.stream()
                .map(task -> toSnapshot(task, latestByTaskId.get(task.getId())))
                .toList();
    }

    private SummaryTaskSnapshot toSnapshot(Task task, CalendarOutbox latestOutbox) {
        return SummaryTaskSnapshot.of(
                task,
                classify(task, latestOutbox),
                latestOutbox != null ? latestOutbox.getStatus() : null,
                latestOutbox != null ? latestOutbox.getOpType() : null,
                latestOutbox != null ? latestOutbox.getLastError() : null
        );
    }

    private TaskSyncState classify(Task task, CalendarOutbox latestOutbox) {
        boolean syncEnabled = Boolean.TRUE.equals(task.getCalendarSyncEnabled());
        boolean hasEventId = task.getCalendarEventId() != null && !task.getCalendarEventId().isBlank();

        if (latestOutbox != null) {
            // 구글 연동이 없어 워커가 건너뛴 건이다. 처리 대기가 아니라 종결이므로
            // 여기서 걸러내지 않으면 아래 분기가 PENDING_SYNC로 떨어뜨려 화면에 영구 "대기"가 뜬다.
            if (latestOutbox.getStatus() == OutboxStatus.SKIPPED) {
                return TaskSyncState.SYNC_DISABLED;
            }

            if (latestOutbox.getOpType() == OutboxOpType.UPSERT) {
                return classifyUpsert(syncEnabled, hasEventId, latestOutbox.getStatus());
            }
            return classifyDelete(syncEnabled, latestOutbox.getStatus());
        }

        if (!syncEnabled) {
            return TaskSyncState.SYNC_DISABLED;
        }

        if (!hasEventId) {
            return TaskSyncState.PENDING_SYNC;
        }

        return TaskSyncState.SYNCED;
    }

    private TaskSyncState classifyUpsert(boolean syncEnabled, boolean hasEventId, OutboxStatus status) {
        if (!syncEnabled) {
            return TaskSyncState.SYNC_DISABLED;
        }

        if (status == OutboxStatus.SUCCESS && hasEventId) {
            return TaskSyncState.SYNCED;
        }

        if (status == OutboxStatus.FAILED) {
            return TaskSyncState.FAILED_SYNC;
        }

        return TaskSyncState.PENDING_SYNC;
    }

    private TaskSyncState classifyDelete(boolean syncEnabled, OutboxStatus status) {
        if (syncEnabled) {
            if (status == OutboxStatus.FAILED) {
                return TaskSyncState.FAILED_SYNC;
            }
            return TaskSyncState.PENDING_SYNC;
        }

        if (status == OutboxStatus.SUCCESS) {
            return TaskSyncState.SYNC_DISABLED;
        }

        if (status == OutboxStatus.FAILED) {
            return TaskSyncState.DELETE_FAILED;
        }

        return TaskSyncState.DELETE_PENDING;
    }
}
