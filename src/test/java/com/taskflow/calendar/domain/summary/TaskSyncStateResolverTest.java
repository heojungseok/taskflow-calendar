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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        project = Project.of("Phase2 Sync Split Demo 150841", 1L);
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
    @DisplayName("resolve_건너뛴업서트면_연동안함 (대기로 보이면 안 된다)")
    void resolve_skippedUpsert_returnsSyncDisabled() {
        Task task = task(
                "구글 연동 없는 사용자의 작업",
                "동기화를 켜두었지만 계정에 구글 연동이 없어 워커가 호출 없이 종결한 건이다.",
                true,
                null
        );
        CalendarOutbox latest = CalendarOutbox.forUpsert(1L, "{}");
        latest.markAsProcessing();
        latest.markAsSkipped("구글 연동 없음. userId=1");

        when(calendarOutboxService.findLatestByTaskId(task.getId())).thenReturn(Optional.of(latest));

        SummaryTaskSnapshot snapshot = resolver.resolve(task);

        // SKIPPED는 종결이다. PENDING_SYNC면 화면에 영구 "대기"가 뜬다.
        assertEquals(TaskSyncState.SYNC_DISABLED, snapshot.getSyncState());
    }

    @Test
    @DisplayName("resolve_건너뛴삭제면_연동안함")
    void resolve_skippedDelete_returnsSyncDisabled() {
        Task task = task(
                "삭제 요청이 건너뛰어진 작업",
                "삭제 동기화 요청이 적재됐지만 구글 연동이 없어 호출 없이 종결됐다.",
                true,
                "evt-999"
        );
        CalendarOutbox latest = CalendarOutbox.forDelete(1L, "{}");
        latest.markAsProcessing();
        latest.markAsSkipped("구글 연동 없음. userId=1");

        when(calendarOutboxService.findLatestByTaskId(task.getId())).thenReturn(Optional.of(latest));

        SummaryTaskSnapshot snapshot = resolver.resolve(task);

        assertEquals(TaskSyncState.SYNC_DISABLED, snapshot.getSyncState());
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

    @Test
    @DisplayName("resolveAll_목록크기와무관하게_Outbox조회는한번이다")
    void resolveAll_queriesOutboxOnce() throws Exception {
        Task first = taskWithId(11L, "첫 번째", true, "evt-1");
        Task second = taskWithId(12L, "두 번째", true, null);
        CalendarOutbox latest = CalendarOutbox.forUpsert(11L, "{}");
        latest.markAsProcessing();
        latest.markAsSuccess();
        when(calendarOutboxService.findLatestByTaskIds(List.of(11L, 12L)))
                .thenReturn(Map.of(11L, latest));

        List<SummaryTaskSnapshot> snapshots = resolver.resolveAll(List.of(first, second));

        assertEquals(2, snapshots.size());
        assertEquals(TaskSyncState.SYNCED, snapshots.get(0).getSyncState());
        assertEquals(TaskSyncState.PENDING_SYNC, snapshots.get(1).getSyncState());
        verify(calendarOutboxService, times(1)).findLatestByTaskIds(List.of(11L, 12L));
        verify(calendarOutboxService, never()).findLatestByTaskId(any());
    }

    private Task taskWithId(Long id, String title, boolean calendarSyncEnabled, String eventId) throws Exception {
        Task task = task(title, "설명", calendarSyncEnabled, eventId);
        var field = Task.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(task, id);
        return task;
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
