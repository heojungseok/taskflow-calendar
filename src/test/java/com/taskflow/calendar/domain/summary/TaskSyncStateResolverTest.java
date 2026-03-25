package com.taskflow.calendar.domain.summary;

import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.calendar.domain.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskSyncStateResolverTest {

    @Mock
    private CalendarOutboxService calendarOutboxService;

    private TaskSyncStateResolver resolver;
    private Project project;

    @BeforeEach
    void setUp() {
        resolver = new TaskSyncStateResolver(calendarOutboxService);
        project = Project.of("Phase2 Sync Split Demo 150841");
    }

    @Test
    @DisplayName("resolve_성공한업서트와이벤트아이디가있으면_동기화")
    void resolve_syncedTask_returnsSynced() {
        Task task = task(
                "Google Calendar 반영 완료",
                "디자인 리뷰 회의가 18시에 Google Calendar에 이미 생성되었고, 회의 링크와 안건이 최신 상태로 반영돼 있다.",
                true,
                "evt-123"
        );
        CalendarOutbox latest = CalendarOutbox.forUpsert(1L, "{}");
        latest.markAsProcessing();
        latest.markAsSuccess();

        when(calendarOutboxService.findLatestByTaskId(task.getId())).thenReturn(Optional.of(latest));

        SummaryTaskSnapshot snapshot = resolver.resolve(task);

        assertEquals(TaskSyncState.SYNCED, snapshot.getSyncState());
    }

    @Test
    @DisplayName("resolve_업서트대기중이면_미동기화대기상태")
    void resolve_pendingUpsert_returnsPendingSync() {
        Task task = task(
                "캘린더 반영 대기",
                "배포 체크리스트 작업을 오늘 17시 일정으로 올리려고 했지만, 아직 worker가 실행되지 않아 캘린더에 반영되지 않았다.",
                true,
                null
        );
        CalendarOutbox latest = CalendarOutbox.forUpsert(1L, "{}");

        when(calendarOutboxService.findLatestByTaskId(task.getId())).thenReturn(Optional.of(latest));

        SummaryTaskSnapshot snapshot = resolver.resolve(task);

        assertEquals(TaskSyncState.PENDING_SYNC, snapshot.getSyncState());
    }

    @Test
    @DisplayName("resolve_업서트실패면_미동기화실패상태")
    void resolve_failedUpsert_returnsFailedSync() {
        Task task = task(
                "동기화 실패 작업",
                "고객 미팅 일정을 캘린더에 반영하려 했지만 Google API 오류가 발생해 재시도가 필요한 상태다.",
                true,
                "evt-old"
        );
        CalendarOutbox latest = CalendarOutbox.forUpsert(1L, "{}");
        latest.markAsProcessing();
        latest.markAsFailed("google api timeout");

        when(calendarOutboxService.findLatestByTaskId(task.getId())).thenReturn(Optional.of(latest));

        SummaryTaskSnapshot snapshot = resolver.resolve(task);

        assertEquals(TaskSyncState.FAILED_SYNC, snapshot.getSyncState());
    }

    @Test
    @DisplayName("resolve_동기화비활성이고아웃박스없으면_미사용상태")
    void resolve_syncDisabledWithoutOutbox_returnsSyncDisabled() {
        Task task = task(
                "개인 메모 정리",
                "개인적으로 참고할 메모를 정리하는 작업으로, 일정 등록이 필요하지 않아 캘린더 동기화를 사용하지 않는다.",
                false,
                null
        );

        when(calendarOutboxService.findLatestByTaskId(task.getId())).thenReturn(Optional.empty());

        SummaryTaskSnapshot snapshot = resolver.resolve(task);

        assertEquals(TaskSyncState.SYNC_DISABLED, snapshot.getSyncState());
    }

    private Task task(String title, String description, boolean calendarSyncEnabled, String eventId) {
        Task task = Task.createTask(
                project,
                title,
                description,
                null,
                null,
                LocalDateTime.now().plusDays(1),
                calendarSyncEnabled
        );
        if (eventId != null) {
            task.setCalendarEventId(eventId);
        }
        if (task.getStatus() != TaskStatus.REQUESTED) {
            task.changeStatus(TaskStatus.REQUESTED);
        }
        return task;
    }
}
