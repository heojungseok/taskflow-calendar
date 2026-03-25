package com.taskflow.calendar.domain.summary;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.project.exception.ProjectNotFoundException;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResult;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.calendar.domain.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectWeeklySummaryServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private WeeklySummaryGenerator weeklySummaryGenerator;

    @Mock
    private TaskSyncStateResolver taskSyncStateResolver;

    private ProjectWeeklySummaryService service;

    private Project project;

    @BeforeEach
    void setUp() {
        service = new ProjectWeeklySummaryService(projectRepository, taskRepository, weeklySummaryGenerator, taskSyncStateResolver);
        project = Project.of("TaskFlow");
    }

    @Test
    @DisplayName("generateWeeklySummary_프로젝트없음_예외발생")
    void generateWeeklySummary_projectNotFound() {
        when(projectRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.generateWeeklySummary(1L));
    }

    @Test
    @DisplayName("generateWeeklySummary_Task없음_로컬빈요약반환")
    void generateWeeklySummary_noTasks_returnsEmptySummary() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of());

        WeeklySummaryResponse response = service.generateWeeklySummary(1L);

        assertEquals("이번 주에 Google Calendar에 반영된 일정이 없습니다.", response.getSynced().getSummary());
        assertEquals("이번 주에 아직 Google Calendar에 반영되지 않은 일정은 없습니다.", response.getUnsynced().getSummary());
        assertEquals(0, response.getTotalTaskCount());
        assertEquals(0, response.getSyncedTaskCount());
        assertEquals(0, response.getUnsyncedTaskCount());
        assertEquals("local-empty-state", response.getSynced().getModel());
        verify(weeklySummaryGenerator, never()).generate(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("generateWeeklySummary_동기화여부별로분리하여요약반환")
    void generateWeeklySummary_withTasks_returnsGroupedSummary() {
        Task syncedTask = task("API 설계", TaskStatus.IN_PROGRESS, LocalDateTime.now().plusDays(1), true, "evt-123");
        Task unsyncedTask = task("문서 정리", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(3), false, null);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(syncedTask, unsyncedTask));
        when(taskSyncStateResolver.resolve(syncedTask))
                .thenReturn(snapshot(syncedTask, TaskSyncState.SYNCED));
        when(taskSyncStateResolver.resolve(unsyncedTask))
                .thenReturn(snapshot(unsyncedTask, TaskSyncState.SYNC_DISABLED));
        when(weeklySummaryGenerator.generate(eq(project), any(), any(), any(), eq(1), eq(SummaryBucket.SYNCED)))
                .thenReturn(WeeklySummaryResult.of(
                        "이번 주에는 캘린더에 반영된 API 설계 일정을 중심으로 진행합니다.",
                        List.of("API 설계 마무리"),
                        List.of("API 설계 일정이 밀리면 후속 일정이 지연될 수 있습니다."),
                        List.of("API 설계 검토를 완료하세요."),
                        "gemini-2.5-flash"
                ));
        when(weeklySummaryGenerator.generate(eq(project), any(), any(), any(), eq(1), eq(SummaryBucket.UNSYNCED)))
                .thenReturn(WeeklySummaryResult.of(
                        "이번 주에는 아직 캘린더에 반영되지 않은 문서 정리 Task가 남아 있습니다.",
                        List.of("문서 정리 일정 확정 필요"),
                        List.of("문서 정리가 누락되면 공유 일정이 불명확해질 수 있습니다."),
                        List.of("문서 정리 일정을 캘린더에 반영하세요."),
                        "gemini-2.5-flash"
                ));

        WeeklySummaryResponse response = service.generateWeeklySummary(1L);

        verify(weeklySummaryGenerator).generate(
                eq(project),
                argThat(tasks -> tasks.size() == 1 && "API 설계".equals(tasks.get(0).getTask().getTitle())),
                any(),
                any(),
                eq(1),
                eq(SummaryBucket.SYNCED)
        );
        verify(weeklySummaryGenerator).generate(
                eq(project),
                argThat(tasks -> tasks.size() == 1 && "문서 정리".equals(tasks.get(0).getTask().getTitle())),
                any(),
                any(),
                eq(1),
                eq(SummaryBucket.UNSYNCED)
        );

        assertEquals(2, response.getTotalTaskCount());
        assertEquals(1, response.getSyncedTaskCount());
        assertEquals(1, response.getUnsyncedTaskCount());
        assertEquals("이번 주에는 캘린더에 반영된 API 설계 일정을 중심으로 진행합니다.", response.getSynced().getSummary());
        assertEquals("이번 주에는 아직 캘린더에 반영되지 않은 문서 정리 Task가 남아 있습니다.", response.getUnsynced().getSummary());
    }

    @Test
    @DisplayName("generateWeeklySummary_이벤트아이디없으면_미동기화로분류")
    void generateWeeklySummary_withoutEventId_goesToUnsynced() {
        Task pendingSyncTask = task("캘린더 대기", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(1), true, null);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(pendingSyncTask));
        when(taskSyncStateResolver.resolve(pendingSyncTask))
                .thenReturn(snapshot(pendingSyncTask, TaskSyncState.PENDING_SYNC));
        when(weeklySummaryGenerator.generate(eq(project), any(), any(), any(), eq(1), eq(SummaryBucket.UNSYNCED)))
                .thenReturn(WeeklySummaryResult.of(
                        "캘린더 반영 대기 중인 Task가 있습니다.",
                        List.of(),
                        List.of("일정 누락 가능성이 있습니다."),
                        List.of("캘린더 동기화 상태를 확인하세요."),
                        "gemini-2.5-flash"
                ));

        WeeklySummaryResponse response = service.generateWeeklySummary(1L);

        verify(weeklySummaryGenerator, never()).generate(eq(project), any(), any(), any(), anyInt(), eq(SummaryBucket.SYNCED));
        verify(weeklySummaryGenerator).generate(eq(project), any(), any(), any(), eq(1), eq(SummaryBucket.UNSYNCED));
        assertEquals(0, response.getSyncedTaskCount());
        assertEquals(1, response.getUnsyncedTaskCount());
    }

    @Test
    @DisplayName("generateWeeklySummary_startAt기반이벤트는_dueAt이멀어도우선반영")
    void generateWeeklySummary_eventWindowPriority_withStartAt() {
        Task rangedTask = Task.createTask(
                project,
                "이번주 착수-장기 일정",
                "이번 주 착수하지만 마감은 다음 달 일정",
                null,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(20),
                false
        );
        Task noStartTask = Task.createTask(
                project,
                "마감만 먼 일정",
                "이번 주 이벤트 신호가 없는 일정",
                null,
                null,
                LocalDateTime.now().plusDays(20),
                false
        );

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(noStartTask, rangedTask));
        when(taskSyncStateResolver.resolve(rangedTask))
                .thenReturn(snapshot(rangedTask, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(noStartTask))
                .thenReturn(snapshot(noStartTask, TaskSyncState.SYNC_DISABLED));
        when(weeklySummaryGenerator.generate(eq(project), any(), any(), any(), eq(2), eq(SummaryBucket.UNSYNCED)))
                .thenReturn(WeeklySummaryResult.of("요약", List.of(), List.of(), List.of(), "gemini-2.5-flash"));

        service.generateWeeklySummary(1L);

        verify(weeklySummaryGenerator).generate(
                eq(project),
                argThat(tasks -> tasks.size() == 2
                        && "이번주 착수-장기 일정".equals(tasks.get(0).getTask().getTitle())
                        && "마감만 먼 일정".equals(tasks.get(1).getTask().getTitle())),
                any(),
                any(),
                eq(2),
                eq(SummaryBucket.UNSYNCED)
        );
    }

    @Test
    @DisplayName("generateWeeklySummary_최근수정설명Task는_우선순위정렬에가산된다")
    void generateWeeklySummary_recentUpdatePriorityBoost() throws Exception {
        Task recentDescribed = task("최근 수정 일정", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(9), true, "evt-a");
        Task staleDescribed = task("기존 일정", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(9), true, "evt-b");

        setField(recentDescribed, "description", "리스크와 의존성이 있는 최신 수정 설명");
        setField(staleDescribed, "description", "리스크와 의존성이 있는 기존 설명");
        setField(recentDescribed, "updatedAt", LocalDateTime.now().minusHours(1));
        setField(staleDescribed, "updatedAt", LocalDateTime.now().minusHours(48));

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(staleDescribed, recentDescribed));
        when(taskSyncStateResolver.resolve(recentDescribed)).thenReturn(snapshot(recentDescribed, TaskSyncState.SYNCED));
        when(taskSyncStateResolver.resolve(staleDescribed)).thenReturn(snapshot(staleDescribed, TaskSyncState.SYNCED));
        when(weeklySummaryGenerator.generate(eq(project), any(), any(), any(), eq(2), eq(SummaryBucket.SYNCED)))
                .thenReturn(WeeklySummaryResult.of("요약", List.of(), List.of(), List.of(), "gemini-2.5-flash"));

        service.generateWeeklySummary(1L);

        verify(weeklySummaryGenerator).generate(
                eq(project),
                argThat(tasks -> tasks.size() == 2
                        && "최근 수정 일정".equals(tasks.get(0).getTask().getTitle())
                        && "기존 일정".equals(tasks.get(1).getTask().getTitle())),
                any(),
                any(),
                eq(2),
                eq(SummaryBucket.SYNCED)
        );
    }

    private Task task(String title, TaskStatus status, LocalDateTime dueAt, boolean calendarSyncEnabled, String eventId) {
        Task task = Task.createTask(project, title, "설명", null, null, dueAt, calendarSyncEnabled);
        if (status != TaskStatus.REQUESTED) {
            task.changeStatus(status);
        }
        if (eventId != null) {
            task.setCalendarEventId(eventId);
        }
        return task;
    }

    private SummaryTaskSnapshot snapshot(Task task, TaskSyncState syncState) {
        return SummaryTaskSnapshot.of(task, syncState, null, null, null);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
