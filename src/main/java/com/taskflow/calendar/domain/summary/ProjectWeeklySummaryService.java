package com.taskflow.calendar.domain.summary;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.project.exception.ProjectNotFoundException;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummarySectionResponse;
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

    private static final int MAX_TASKS_PER_SECTION = 15;
    private static final int DEFAULT_EVENT_DURATION_HOURS = 1;
    private static final List<String> HIGH_PRIORITY_DESCRIPTION_KEYWORDS = List.of(
            "긴급", "urgent", "asap", "즉시", "오늘", "차단", "blocked", "장애", "incident", "배포", "release"
    );
    private static final List<String> RISK_DESCRIPTION_KEYWORDS = List.of(
            "리스크", "risk", "실패", "failure", "누락", "지연", "retry", "의존", "dependency"
    );

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final WeeklySummaryGenerator weeklySummaryGenerator;
    private final TaskSyncStateResolver taskSyncStateResolver;

    public WeeklySummaryResponse generateWeeklySummary(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        List<Task> allTasks = taskRepository.findAllByProjectIdAndDeletedFalse(projectId);
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDateTime generatedAt = LocalDateTime.now();
        List<SummaryTaskSnapshot> prioritizedTasks = allTasks.stream()
                .map(taskSyncStateResolver::resolve)
                .sorted(taskPriorityComparator(today))
                .collect(Collectors.toList());
        List<SummaryTaskSnapshot> syncedTasks = prioritizedTasks.stream()
                .filter(snapshot -> snapshot.getSyncState().isSynced())
                .collect(Collectors.toList());
        List<SummaryTaskSnapshot> unsyncedTasks = prioritizedTasks.stream()
                .filter(snapshot -> !snapshot.getSyncState().isSynced())
                .collect(Collectors.toList());

        WeeklySummarySectionResponse synced = buildSection(
                project,
                syncedTasks,
                weekStart,
                weekEnd,
                SummaryBucket.SYNCED
        );
        WeeklySummarySectionResponse unsynced = buildSection(
                project,
                unsyncedTasks,
                weekStart,
                weekEnd,
                SummaryBucket.UNSYNCED
        );

        return WeeklySummaryResponse.of(
                project,
                weekStart,
                weekEnd,
                generatedAt,
                allTasks.size(),
                syncedTasks.size(),
                unsyncedTasks.size(),
                synced,
                unsynced
        );
    }

    private WeeklySummarySectionResponse buildSection(Project project,
                                                      List<SummaryTaskSnapshot> tasks,
                                                      LocalDate weekStart,
                                                      LocalDate weekEnd,
                                                      SummaryBucket bucket) {
        if (tasks.isEmpty()) {
            return WeeklySummarySectionResponse.of(
                    0,
                    0,
                    WeeklySummaryResult.empty(bucket.getEmptySummary(), bucket.getEmptyNextActions())
            );
        }

        List<SummaryTaskSnapshot> includedTasks = tasks.stream()
                .limit(MAX_TASKS_PER_SECTION)
                .collect(Collectors.toList());

        WeeklySummaryResult result = weeklySummaryGenerator.generate(
                project,
                includedTasks,
                weekStart,
                weekEnd,
                tasks.size(),
                bucket
        );

        return WeeklySummarySectionResponse.of(tasks.size(), includedTasks.size(), result);
    }

    private Comparator<SummaryTaskSnapshot> taskPriorityComparator(LocalDate today) {
        return Comparator
                .comparingInt((SummaryTaskSnapshot snapshot) -> taskPriority(snapshot.getTask(), today))
                .reversed()
                .thenComparing(snapshot -> effectiveEventEndAt(snapshot.getTask()), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(snapshot -> snapshot.getTask().getCreatedAt(), Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private int taskPriority(Task task, LocalDate today) {
        int score = 0;
        score += schedulePriority(task, today);

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

        score += descriptionPriority(task.getDescription());

        return score;
    }

    private int schedulePriority(Task task, LocalDate today) {
        int score = 0;
        LocalDateTime deadlineAt = task.getDueAt();

        if (deadlineAt != null) {
            LocalDate deadlineDate = deadlineAt.toLocalDate();
            if (deadlineDate.isBefore(today) && task.getStatus() != TaskStatus.DONE) {
                score += 100;
            } else if (!deadlineDate.isAfter(today.plusDays(7))) {
                score += 35;
            }
        }

        LocalDateTime eventStartAt = effectiveEventStartAt(task);
        LocalDateTime eventEndAt = effectiveEventEndAt(task);

        if (eventStartAt != null && eventEndAt != null) {
            LocalDateTime windowStart = today.atStartOfDay();
            LocalDateTime windowEndExclusive = today.plusDays(8).atStartOfDay();

            if (overlaps(eventStartAt, eventEndAt, windowStart, windowEndExclusive)) {
                score += 45;
            } else if (!eventStartAt.isAfter(today.plusDays(14).atStartOfDay())) {
                score += 20;
            }
        }

        return score;
    }

    private LocalDateTime effectiveEventStartAt(Task task) {
        if (task.getStartAt() != null) {
            return task.getStartAt();
        }
        if (task.getDueAt() != null) {
            return task.getDueAt().minusHours(DEFAULT_EVENT_DURATION_HOURS);
        }
        return null;
    }

    private LocalDateTime effectiveEventEndAt(Task task) {
        return task.getDueAt();
    }

    private boolean overlaps(LocalDateTime startAt,
                             LocalDateTime endAt,
                             LocalDateTime windowStart,
                             LocalDateTime windowEndExclusive) {
        return startAt.isBefore(windowEndExclusive) && endAt.isAfter(windowStart);
    }

    private int descriptionPriority(String description) {
        if (description == null || description.isBlank()) {
            return 0;
        }

        String normalized = description.toLowerCase();
        int score = 0;

        for (String keyword : HIGH_PRIORITY_DESCRIPTION_KEYWORDS) {
            if (normalized.contains(keyword)) {
                score += 8;
            }
        }

        for (String keyword : RISK_DESCRIPTION_KEYWORDS) {
            if (normalized.contains(keyword)) {
                score += 5;
            }
        }

        return Math.min(score, 30);
    }
}
