package com.taskflow.calendar.domain.task;

import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.task.dto.DeleteTaskResponse;
import com.taskflow.calendar.domain.user.User;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.calendar.domain.task.exception.TaskNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final Long TASK_ID = 10L;
    private static final Long USER_ID = 7L;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TaskHistoryRepository historyRepository;

    @Mock
    private CalendarOutboxService calendarOutboxService;

    @InjectMocks
    private TaskService taskService;

    private Task task;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        Project project = Project.of("TaskFlow");
        user = User.createGoogleUser("tester@example.com", "tester");
        setField(user, "id", USER_ID);

        task = Task.createTask(
                project,
                "삭제 테스트",
                "soft delete 후 calendar delete enqueue",
                null,
                LocalDateTime.of(2026, 3, 27, 9, 0),
                LocalDateTime.of(2026, 3, 27, 10, 0),
                true
        );
        setField(task, "id", TASK_ID);
        task.setCalendarEventId("event-123");
    }

    @Test
    @DisplayName("deleteTask는 soft delete 후 DELETE outbox를 적재한다")
    void deleteTask_softDeletesAndEnqueuesDelete() {
        when(taskRepository.findByIdAndDeletedFalse(TASK_ID)).thenReturn(Optional.of(task));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        DeleteTaskResponse response = taskService.deleteTask(TASK_ID, USER_ID);

        assertEquals(TASK_ID, response.getDeletedTaskId());
        assertTrue(task.getDeleted());
        assertNotNull(task.getDeletedAt());
        verify(calendarOutboxService).enqueueDelete(task);

        ArgumentCaptor<TaskHistory> captor = ArgumentCaptor.forClass(TaskHistory.class);
        verify(historyRepository).save(captor.capture());
        TaskHistory savedHistory = captor.getValue();
        assertEquals(TaskChangeType.CONTENT, savedHistory.getChangeType());
        assertEquals("deleted=true", savedHistory.getAfterValue());
        assertEquals(user, savedHistory.getChangedByUser());
    }

    @Test
    @DisplayName("deleteTask는 이미 삭제된 task를 다시 조회하지 못하면 예외를 던진다")
    void deleteTask_throwsWhenTaskMissing() {
        when(taskRepository.findByIdAndDeletedFalse(TASK_ID)).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> taskService.deleteTask(TASK_ID, USER_ID));
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
