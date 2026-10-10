package com.taskflow.calendarsync.outbox;

import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Task 변경을 Outbox로 적재한다.
 *
 * <p>Transactional Outbox라서 Task 변경과 같은 트랜잭션에서 실행돼야 한다. 그래서 @TransactionalEventListener가 아니라 동기
 * {@code @EventListener}를 쓴다. 적재가 실패하면 Task 변경도 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class TaskCalendarSyncListener {

    private final CalendarOutboxService calendarOutboxService;

    @EventListener
    public void on(TaskChangedEvent event) {
        Task task = event.task();
        switch (event.type()) {
            case CREATED, STATUS_CHANGED -> {
                if (task.isCalendarSyncActive()) {
                    calendarOutboxService.enqueueUpsert(task); // DONE이면 [DONE] prefix 추가
                }
            }
            case UPDATED -> {
                if (task.isCalendarSyncActive()) {
                    calendarOutboxService.enqueueUpsert(task);
                } else if (task.getCalendarEventId() != null) {
                    // 동기화를 끄거나 마감일을 지우면 이미 만든 일정을 지운다
                    calendarOutboxService.enqueueDelete(task);
                }
            }
            case DELETED -> calendarOutboxService.enqueueDelete(task);
        }
    }
}
