package com.taskflow.assistant.summary;

import com.taskflow.assistant.summary.generator.SummaryPromptTaskSupport;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 주간 요약에 넣을 Task의 우선순위와, 섹션마다 실제로 프롬프트에 담을 대표 Task를 고른다. */
final class SummaryTaskSelector {

    private static final int DEFAULT_EVENT_DURATION_HOURS = 1;
    private static final int RECENT_UPDATE_HOURS = 24;
    private static final int MAX_SYNCED_TASKS_PER_SECTION = 6;
    private static final int MAX_UNSYNCED_TASKS_PER_SECTION = 4;
    private static final int MAX_SYNCED_DESC_BRIEF_CHARS = 280;
    private static final int MAX_UNSYNCED_DESC_BRIEF_CHARS = 160;
    private static final int MAX_DONE_TASKS_PER_SECTION = 1;
    private static final List<String> HIGH_PRIORITY_DESCRIPTION_KEYWORDS =
            List.of("긴급", "urgent", "asap", "즉시", "오늘", "차단", "blocked", "장애", "incident", "배포", "release");
    private static final List<String> RISK_DESCRIPTION_KEYWORDS =
            List.of("리스크", "risk", "실패", "failure", "누락", "지연", "retry", "의존", "dependency");

    private final SummaryPromptTaskSupport promptTaskSupport = new SummaryPromptTaskSupport();

    Comparator<TaskSyncSnapshot> taskPriorityComparator(LocalDate today) {
        return Comparator.comparingInt((TaskSyncSnapshot snapshot) -> taskPriority(snapshot.getTask(), today))
                .reversed()
                .thenComparing(
                        snapshot -> effectiveEventEndAt(snapshot.getTask()),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                        snapshot -> snapshot.getTask().getCreatedAt(), Comparator.nullsLast(Comparator.naturalOrder()));
    }

    List<TaskSyncSnapshot> selectIncludedTasks(List<TaskSyncSnapshot> tasks, SummaryBucket bucket) {
        if (tasks.isEmpty()) {
            return List.of();
        }

        int maxTasks = bucket == SummaryBucket.SYNCED ? MAX_SYNCED_TASKS_PER_SECTION : MAX_UNSYNCED_TASKS_PER_SECTION;
        int maxDescBriefChars =
                bucket == SummaryBucket.SYNCED ? MAX_SYNCED_DESC_BRIEF_CHARS : MAX_UNSYNCED_DESC_BRIEF_CHARS;

        List<TaskSyncSnapshot> selected = new java.util.ArrayList<>();
        int selectedDescBriefChars = 0;
        int doneCount = 0;

        for (TaskSyncSnapshot snapshot : tasks) {
            if (selected.size() >= maxTasks) {
                break;
            }

            Task task = snapshot.getTask();
            if (task.getStatus() == TaskStatus.DONE && doneCount >= MAX_DONE_TASKS_PER_SECTION) {
                continue;
            }

            int descBriefChars = promptTaskSupport.descBriefChars(task);
            boolean firstTask = selected.isEmpty();
            boolean withinDescBudget = firstTask || selectedDescBriefChars + descBriefChars <= maxDescBriefChars;
            if (!withinDescBudget) {
                continue;
            }

            selected.add(snapshot);
            selectedDescBriefChars += descBriefChars;
            if (task.getStatus() == TaskStatus.DONE) {
                doneCount++;
            }
        }

        if (selected.isEmpty()) {
            selected.add(tasks.get(0));
        }

        return selected;
    }

    private int taskPriority(Task task, LocalDate today) {
        int score = 0;
        score += schedulePriority(task, today);

        switch (task.getStatus()) {
            case IN_PROGRESS -> score += 30;
            case REQUESTED -> score += 20;
            case BLOCKED -> score += 10;
            case DONE -> score -= 20;
            default -> {}
        }

        if (Boolean.TRUE.equals(task.getCalendarSyncEnabled())) {
            score += 5;
        }

        score += descriptionPriority(task.getDescription());
        score += recentlyUpdatedPriority(task);

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

    private boolean overlaps(
            LocalDateTime startAt, LocalDateTime endAt, LocalDateTime windowStart, LocalDateTime windowEndExclusive) {
        return startAt.isBefore(windowEndExclusive) && endAt.isAfter(windowStart);
    }

    private int descriptionPriority(String description) {
        if (description == null || description.isBlank()) {
            return 0;
        }

        String normalized = description.toLowerCase(Locale.ROOT);
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

    private int recentlyUpdatedPriority(Task task) {
        if (task.getUpdatedAt() == null) {
            return 0;
        }
        if (task.getDescription() == null || task.getDescription().isBlank()) {
            return 0;
        }

        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).minusHours(RECENT_UPDATE_HOURS);
        if (task.getUpdatedAt().isAfter(threshold)) {
            return 25;
        }
        return 0;
    }
}
