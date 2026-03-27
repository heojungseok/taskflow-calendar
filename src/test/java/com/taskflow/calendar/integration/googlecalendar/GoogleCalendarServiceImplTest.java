package com.taskflow.calendar.integration.googlecalendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.OutboxOpType;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.calendar.domain.task.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long TASK_ID = 10L;

    @Mock
    private GoogleCalendarClient googleCalendarClient;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private GoogleCalendarServiceImpl googleCalendarService;

    @Test
    @DisplayName("UPSERT 생성 시 Task.startAt을 Google 이벤트 시작 시각으로 사용한다")
    void handle_upsertCreate_usesTaskStartAt() throws Exception {
        LocalDateTime startAt = LocalDateTime.of(2026, 3, 27, 9, 0);
        LocalDateTime dueAt = LocalDateTime.of(2026, 3, 29, 18, 0);
        Task task = taskWithSchedule(TASK_ID, startAt, dueAt, null);
        CalendarOutbox outbox = upsertOutbox(TASK_ID);

        when(objectMapper.readValue(eq(outbox.getPayload()), eq(Map.class)))
                .thenReturn(payloadMap(TASK_ID, USER_ID));
        when(taskRepository.findByIdAndDeletedFalse(TASK_ID)).thenReturn(Optional.of(task));
        when(googleCalendarClient.createEvent(eq(USER_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn("new-event-id");

        googleCalendarService.handle(outbox);

        ArgumentCaptor<CalendarEventDto> captor = ArgumentCaptor.forClass(CalendarEventDto.class);
        verify(googleCalendarClient).createEvent(eq(USER_ID), captor.capture());
        CalendarEventDto sent = captor.getValue();

        assertEquals(startAt, sent.getStartAt());
        assertEquals(dueAt, sent.getEndAt());
    }

    @Test
    @DisplayName("UPSERT 업데이트 시 startAt이 없으면 dueAt-1시간 fallback을 사용한다")
    void handle_upsertUpdate_fallbackWhenStartAtMissing() throws Exception {
        LocalDateTime dueAt = LocalDateTime.of(2026, 3, 29, 18, 0);
        Task task = taskWithSchedule(TASK_ID, null, dueAt, "event-123");
        CalendarOutbox outbox = upsertOutbox(TASK_ID);

        when(objectMapper.readValue(eq(outbox.getPayload()), eq(Map.class)))
                .thenReturn(payloadMap(TASK_ID, USER_ID));
        when(taskRepository.findByIdAndDeletedFalse(TASK_ID)).thenReturn(Optional.of(task));

        googleCalendarService.handle(outbox);

        ArgumentCaptor<CalendarEventDto> captor = ArgumentCaptor.forClass(CalendarEventDto.class);
        verify(googleCalendarClient).updateEvent(eq(USER_ID), eq("event-123"), captor.capture());
        CalendarEventDto sent = captor.getValue();

        assertEquals(dueAt.minusHours(1), sent.getStartAt());
        assertEquals(dueAt, sent.getEndAt());
    }

    @Test
    @DisplayName("DELETE 처리 시 payload eventId 기준으로 Google 이벤트를 삭제한다")
    void handle_delete_callsDeleteEvent() throws Exception {
        CalendarOutbox outbox = deleteOutbox(TASK_ID);

        when(objectMapper.readValue(eq(outbox.getPayload()), eq(Map.class)))
                .thenReturn(payloadMap(TASK_ID, USER_ID, "event-123"));

        googleCalendarService.handle(outbox);

        verify(googleCalendarClient).deleteEvent(USER_ID, "event-123");
        verify(taskRepository, never()).findByIdAndDeletedFalse(TASK_ID);
    }

    @Test
    @DisplayName("DELETE 처리 시 payload eventId가 없으면 no-op 처리한다")
    void handle_delete_withoutEventId_isNoOp() throws Exception {
        CalendarOutbox outbox = deleteOutbox(TASK_ID);

        when(objectMapper.readValue(eq(outbox.getPayload()), eq(Map.class)))
                .thenReturn(payloadMap(TASK_ID, USER_ID, null));

        googleCalendarService.handle(outbox);

        verify(googleCalendarClient, never()).deleteEvent(eq(USER_ID), anyString());
        verify(taskRepository, never()).findByIdAndDeletedFalse(TASK_ID);
    }

    private CalendarOutbox upsertOutbox(Long taskId) {
        return CalendarOutbox.builder()
                .taskId(taskId)
                .opType(OutboxOpType.UPSERT)
                .status(OutboxStatus.PENDING)
                .payload("{\"taskId\":" + taskId + "}")
                .retryCount(0)
                .build();
    }

    private CalendarOutbox deleteOutbox(Long taskId) {
        return CalendarOutbox.builder()
                .taskId(taskId)
                .opType(OutboxOpType.DELETE)
                .status(OutboxStatus.PENDING)
                .payload("{\"taskId\":" + taskId + "}")
                .retryCount(0)
                .build();
    }

    private Map<String, Object> payloadMap(Long taskId, Long userId) {
        return payloadMap(taskId, userId, null);
    }

    private Map<String, Object> payloadMap(Long taskId, Long userId, String eventId) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("taskId", taskId);
        payload.put("meta", Map.of("requestedByUserId", userId));
        if (eventId != null) {
            payload.put("event", Map.of("eventId", eventId));
        } else {
            payload.put("event", Map.of());
        }
        return payload;
    }

    private Task taskWithSchedule(Long id, LocalDateTime startAt, LocalDateTime dueAt, String eventId) throws Exception {
        Project project = Project.of("Google Sync");
        Task task = Task.createTask(project, "일정 테스트", "설명", null, startAt, dueAt, true);
        setField(task, "id", id);
        setField(task, "status", TaskStatus.IN_PROGRESS);
        if (eventId != null) {
            task.setCalendarEventId(eventId);
        }
        return task;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
