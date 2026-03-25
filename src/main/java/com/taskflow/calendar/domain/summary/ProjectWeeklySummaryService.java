package com.taskflow.calendar.domain.summary;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.project.exception.ProjectNotFoundException;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResult;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.calendar.domain.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectWeeklySummaryService {

    private static final int MAX_TASKS_FOR_SUMMARY = 30;

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final WeeklySummaryGenerator weeklySummaryGenerator;

    public WeeklySummaryResponse generateWeeklySummary(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        List<Task> allTasks = taskRepository.findAllByProjectIdAndDeletedFalse(projectId);
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDateTime generatedAt = LocalDateTime.now();

        if (allTasks.isEmpty()) {
            return WeeklySummaryResponse.of(
                    project,
                    weekStart,
                    weekEnd,
                    generatedAt,
                    0,
                    0,
                    WeeklySummaryResult.empty()
            );
        }

        List<Task> includedTasks = allTasks.stream()
                .sorted(taskPriorityComparator(today))
                .limit(MAX_TASKS_FOR_SUMMARY)
                .collect(Collectors.toList());

        WeeklySummaryResult result = weeklySummaryGenerator.generate(
                project,
                includedTasks,
                weekStart,
                weekEnd,
                allTasks.size()
        );

        return WeeklySummaryResponse.of(
                project,
                weekStart,
                weekEnd,
                generatedAt,
                allTasks.size(),
                includedTasks.size(),
                result
        );
    }

    private Comparator<Task> taskPriorityComparator(LocalDate today) {
        return Comparator
                .comparingInt((Task task) -> taskPriority(task, today))
                .reversed()
                .thenComparing(Task::getDueAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private int taskPriority(Task task, LocalDate today) {
        int score = 0;

        if (task.getDueAt() != null) {
            LocalDate dueDate = task.getDueAt().toLocalDate();
            if (dueDate.isBefore(today) && task.getStatus() != TaskStatus.DONE) {
                score += 100;
            } else if (!dueDate.isAfter(today.plusDays(7))) {
                score += 60;
            }
        }

        switch (task.getStatus()) {
            case IN_PROGRESS:
                score += 30;
                break;
            case REQUESTED:
                score += 20;
                break;
            case BLOCKED:
                score += 10;
                break;
            case DONE:
                score -= 20;
                break;
            default:
                break;
        }

        if (Boolean.TRUE.equals(task.getCalendarSyncEnabled())) {
            score += 5;
        }

        return score;
    }
}
