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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class ProjectWeeklySummaryServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private WeeklySummaryGenerator weeklySummaryGenerator;

    private ProjectWeeklySummaryService service;

    private Project project;

    @BeforeEach
    void setUp() {
        service = new ProjectWeeklySummaryService(projectRepository, taskRepository, weeklySummaryGenerator);
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

        assertEquals("이번 주에 요약할 Task가 없습니다.", response.getSummary());
        assertEquals(0, response.getTotalTaskCount());
        assertEquals(0, response.getIncludedTaskCount());
        assertEquals("local-empty-state", response.getModel());
        verify(weeklySummaryGenerator, never()).generate(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("generateWeeklySummary_Task있음_LLM생성결과반환")
    void generateWeeklySummary_withTasks_returnsGeneratedSummary() {
        Task first = task("API 설계", TaskStatus.IN_PROGRESS, LocalDateTime.now().plusDays(1));
        Task second = task("문서 정리", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(3));

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(first, second));
        when(weeklySummaryGenerator.generate(eq(project), any(), any(), any(), eq(2)))
                .thenReturn(WeeklySummaryResult.of(
                        "이번 주에는 API 설계를 마무리하고 문서를 정리하는 것이 핵심입니다.",
                        List.of("API 설계 마무리"),
                        List.of("문서 정리가 지연되면 일정 공유가 늦어질 수 있습니다."),
                        List.of("API 설계 검토를 완료하세요."),
                        "gemini-2.5-flash"
                ));

        WeeklySummaryResponse response = service.generateWeeklySummary(1L);

        ArgumentCaptor<List<Task>> taskCaptor = ArgumentCaptor.forClass(List.class);
        verify(weeklySummaryGenerator).generate(eq(project), taskCaptor.capture(), any(), any(), eq(2));

        assertEquals(2, taskCaptor.getValue().size());
        assertEquals("이번 주에는 API 설계를 마무리하고 문서를 정리하는 것이 핵심입니다.", response.getSummary());
        assertEquals(2, response.getTotalTaskCount());
        assertEquals(2, response.getIncludedTaskCount());
        assertEquals("gemini-2.5-flash", response.getModel());
    }

    private Task task(String title, TaskStatus status, LocalDateTime dueAt) {
        Task task = Task.createTask(project, title, "설명", null, null, dueAt, false);
        if (status != TaskStatus.REQUESTED) {
            task.changeStatus(status);
        }
        return task;
    }
}
