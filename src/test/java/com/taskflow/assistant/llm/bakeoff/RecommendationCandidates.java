package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskStatus;
import com.taskflow.planning.task.TaskSyncState;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** ProjectTaskRecommendationService의 후보 선정(비공개)과 같은 규칙. outbox 상태·updatedAt은 픽스처에 없고 description·dueAt은 항상 있어 점수 18을 일정하게 더한다. */
final class RecommendationCandidates {

    static final int MAX_CANDIDATES = 8;
    private static final int MAX_COUNT = 5;

    private RecommendationCandidates() {}

    static List<TaskSyncSnapshot> select(List<TaskSyncSnapshot> all, LocalDate today) {
        return all.stream()
                .filter(snapshot -> snapshot.getTask().getStatus() != TaskStatus.DONE)
                .sorted(comparator(today))
                .limit(MAX_CANDIDATES)
                .toList();
    }

    static int count(List<TaskSyncSnapshot> all) {
        long eligible = all.stream()
                .filter(snapshot -> snapshot.getTask().getStatus() != TaskStatus.DONE)
                .count();
        return Math.min(MAX_COUNT, Math.max(1, (int) Math.ceil(eligible * 0.3d)));
    }

    private static Comparator<TaskSyncSnapshot> comparator(LocalDate today) {
        return Comparator.comparingInt((TaskSyncSnapshot snapshot) -> score(snapshot, today))
                .reversed()
                .thenComparing(s -> s.getTask().getDueAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(s -> s.getTask().getId());
    }

    private static int score(TaskSyncSnapshot snapshot, LocalDate today) {
        Task task = snapshot.getTask();
        int score =
                switch (task.getStatus()) {
                    case BLOCKED -> 110;
                    case IN_PROGRESS -> 60;
                    case REQUESTED -> 40;
                    default -> 0;
                };
        LocalDate due = task.getDueAt().toLocalDate();
        if (due.isBefore(today)) {
            score += 120;
        } else if (!due.isAfter(today.plusDays(1))) {
            score += 95;
        } else if (!due.isAfter(today.plusDays(3))) {
            score += 70;
        } else if (!due.isAfter(today.plusDays(7))) {
            score += 45;
        }
        TaskSyncState state = snapshot.getSyncState();
        if (state == TaskSyncState.FAILED_SYNC || state == TaskSyncState.DELETE_FAILED) {
            score += 85;
        } else if (state == TaskSyncState.PENDING_SYNC || state == TaskSyncState.DELETE_PENDING) {
            score += 60;
        }
        return score + 18;
    }
}
