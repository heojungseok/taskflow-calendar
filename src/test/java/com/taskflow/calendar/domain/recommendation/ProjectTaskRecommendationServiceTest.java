package com.taskflow.calendar.domain.recommendation;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.project.exception.ProjectNotFoundException;
import com.taskflow.calendar.domain.recommendation.cache.TaskRecommendationCacheService;
import com.taskflow.calendar.domain.recommendation.dto.ProjectTaskRecommendationResponse;
import com.taskflow.calendar.domain.recommendation.dto.TaskRecommendationCacheStatus;
import com.taskflow.calendar.domain.recommendation.generator.TaskRecommendationGenerationResult;
import com.taskflow.calendar.domain.recommendation.generator.TaskRecommendationGenerator;
import com.taskflow.calendar.domain.recommendation.generator.TaskRecommendationItemResult;
import com.taskflow.calendar.domain.summary.SummaryTaskSnapshot;
import com.taskflow.calendar.domain.summary.TaskSyncState;
import com.taskflow.calendar.domain.summary.TaskSyncStateResolver;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.config.GeminiRecommendationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectTaskRecommendationServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskSyncStateResolver taskSyncStateResolver;

    @Mock
    private TaskRecommendationGenerator taskRecommendationGenerator;

    @Mock
    private TaskRecommendationCacheService taskRecommendationCacheService;

    private ProjectTaskRecommendationService service;

    private Project project;

    @BeforeEach
    void setUp() {
        GeminiRecommendationProperties geminiProperties = new GeminiRecommendationProperties();
        geminiProperties.setModel("gemini-2.5-flash");
        service = new ProjectTaskRecommendationService(
                projectRepository,
                taskRepository,
                taskSyncStateResolver,
                taskRecommendationGenerator,
                taskRecommendationCacheService,
                geminiProperties
        );
        project = Project.of("TaskFlow");
    }

    @Test
    @DisplayName("getRecommendations_프로젝트없음_예외발생")
    void getRecommendations_projectNotFound() {
        when(projectRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.getRecommendations(1L));
    }

    @Test
    @DisplayName("getRecommendations_DONE만있으면_빈추천반환")
    void getRecommendations_onlyDoneTasks_returnsEmpty() {
        Task doneTask = task("정리 완료", TaskStatus.DONE, LocalDateTime.now().plusDays(1));

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(doneTask));

        ProjectTaskRecommendationResponse response = service.getRecommendations(1L);

        assertEquals(0, response.getTotalEligibleTaskCount());
        assertEquals(0, response.getRecommendedCount());
        assertEquals(TaskRecommendationCacheStatus.LIVE, response.getCacheStatus());
        verify(taskRecommendationGenerator, never()).generate(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("getRecommendations_미완료Task기준30퍼센트최대5개를적용한다")
    void getRecommendations_appliesRatioAndMaxCount() {
        List<Task> tasks = List.of(
                task("1", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(1)),
                task("2", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(2)),
                task("3", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(3)),
                task("4", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(4)),
                task("5", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(5)),
                task("6", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(6)),
                task("7", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(7)),
                task("8", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(8)),
                task("9", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(9)),
                task("10", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(10)),
                task("11", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(11)),
                task("12", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(12)),
                task("13", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(13)),
                task("14", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(14)),
                task("15", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(15)),
                task("16", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(16)),
                task("17", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(17))
        );

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(tasks);
        for (Task task : tasks) {
            when(taskSyncStateResolver.resolve(task)).thenReturn(snapshot(task, TaskSyncState.SYNC_DISABLED));
        }
        when(taskRecommendationCacheService.isEnabled()).thenReturn(false);
        when(taskRecommendationGenerator.generate(eq(project), any(), eq(5), any()))
                .thenReturn(TaskRecommendationGenerationResult.of(List.of(
                        TaskRecommendationItemResult.of(tasks.get(0).getId(), "태그1", "보조1", "이유1"),
                        TaskRecommendationItemResult.of(tasks.get(1).getId(), "태그2", "보조2", "이유2"),
                        TaskRecommendationItemResult.of(tasks.get(2).getId(), "태그3", null, "이유3"),
                        TaskRecommendationItemResult.of(tasks.get(3).getId(), "태그4", null, "이유4"),
                        TaskRecommendationItemResult.of(tasks.get(4).getId(), "태그5", null, "이유5")
                )));

        ProjectTaskRecommendationResponse response = service.getRecommendations(1L);

        assertEquals(17, response.getTotalEligibleTaskCount());
        assertEquals(5, response.getRecommendedCount());
        assertEquals(5, response.getItems().size());
        verify(taskRecommendationGenerator).generate(eq(project), any(), eq(5), eq(LocalDate.now()));
    }

    @Test
    @DisplayName("getRecommendations_캐시히트면생성기를호출하지않는다")
    void getRecommendations_cacheHit() {
        Task task = task("API 설계", TaskStatus.IN_PROGRESS, LocalDateTime.now().plusDays(1));

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(task));
        when(taskSyncStateResolver.resolve(task)).thenReturn(snapshot(task, TaskSyncState.SYNCED));
        when(taskRecommendationCacheService.isEnabled()).thenReturn(true);
        when(taskRecommendationCacheService.find(any())).thenReturn(Optional.of(
                ProjectTaskRecommendationResponse.of(
                        project,
                        LocalDateTime.now(),
                        TaskRecommendationCacheStatus.LIVE,
                        1,
                        1,
                        List.of()
                )
        ));

        ProjectTaskRecommendationResponse response = service.getRecommendations(1L);

        assertEquals(TaskRecommendationCacheStatus.CACHE_HIT, response.getCacheStatus());
        verify(taskRecommendationGenerator, never()).generate(any(), any(), anyInt(), any());
    }

    private Task task(String title, TaskStatus status, LocalDateTime dueAt) {
        Task task = Task.createTask(project, title, title + " description", null, null, dueAt, true);
        if (status == TaskStatus.IN_PROGRESS || status == TaskStatus.BLOCKED) {
            task.changeStatus(status);
        } else if (status == TaskStatus.DONE) {
            task.changeStatus(TaskStatus.IN_PROGRESS);
            task.changeStatus(TaskStatus.DONE);
        }
        return task;
    }

    private SummaryTaskSnapshot snapshot(Task task, TaskSyncState syncState) {
        return SummaryTaskSnapshot.of(task, syncState, null, null, null);
    }
}
