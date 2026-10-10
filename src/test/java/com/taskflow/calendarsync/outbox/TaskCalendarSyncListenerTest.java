package com.taskflow.calendarsync.outbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.taskflow.planning.project.Project;
import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskChangedEvent;
import com.taskflow.planning.task.TaskChangedEvent.Type;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** TaskService에 있던 Outbox 적재 조건을 이벤트 리스너로 옮긴 뒤에도 같은지 확인한다. */
class TaskCalendarSyncListenerTest {

    private final CalendarOutboxService outboxService = mock(CalendarOutboxService.class);
    private final TaskCalendarSyncListener listener = new TaskCalendarSyncListener(outboxService);

    @Test
    void 생성과_상태변경은_동기화가_켜져_있을_때만_UPSERT를_적재한다() {
        Task active = task(true, null);
        listener.on(new TaskChangedEvent(active, Type.CREATED));
        listener.on(new TaskChangedEvent(active, Type.STATUS_CHANGED));
        verify(outboxService, times(2)).enqueueUpsert(active);

        CalendarOutboxService idle = mock(CalendarOutboxService.class);
        TaskCalendarSyncListener idleListener = new TaskCalendarSyncListener(idle);
        Task inactiveWithEvent = task(false, "event-1");
        idleListener.on(new TaskChangedEvent(inactiveWithEvent, Type.CREATED));
        idleListener.on(new TaskChangedEvent(inactiveWithEvent, Type.STATUS_CHANGED));
        verifyNoInteractions(idle);
    }

    @Test
    void 수정은_동기화가_켜져_있으면_UPSERT_꺼졌는데_기존_일정이_있으면_DELETE를_적재한다() {
        Task active = task(true, null);
        listener.on(new TaskChangedEvent(active, Type.UPDATED));
        verify(outboxService).enqueueUpsert(active);

        Task turnedOff = task(false, "event-1");
        listener.on(new TaskChangedEvent(turnedOff, Type.UPDATED));
        verify(outboxService).enqueueDelete(turnedOff);
    }

    @Test
    void 수정에서_동기화가_꺼져_있고_기존_일정도_없으면_적재하지_않는다() {
        listener.on(new TaskChangedEvent(task(false, null), Type.UPDATED));
        verifyNoInteractions(outboxService);
    }

    @Test
    void 삭제는_항상_DELETE를_적재한다() {
        Task inactive = task(false, null);
        listener.on(new TaskChangedEvent(inactive, Type.DELETED));
        verify(outboxService).enqueueDelete(inactive);
    }

    private static Task task(boolean calendarSyncEnabled, String calendarEventId) {
        Task task = Task.createTask(
                mock(Project.class),
                "제목",
                null,
                null,
                LocalDateTime.of(2026, 10, 10, 9, 0),
                LocalDateTime.of(2026, 10, 10, 10, 0),
                calendarSyncEnabled);
        task.setCalendarEventId(calendarEventId);
        return task;
    }
}
