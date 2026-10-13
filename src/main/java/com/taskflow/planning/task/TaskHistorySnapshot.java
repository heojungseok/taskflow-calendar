package com.taskflow.planning.task;

/** Task 변경 이력에 남기는 before/after 문자열. */
final class TaskHistorySnapshot {

    private TaskHistorySnapshot() {}

    /**
     * Task의 현재 상태를 문자열 스냅샷으로 생성
     */
    static String buildTaskSnapshot(Task task) {
        return String.format(
                "title=%s, description=%s, status=%s, assigneeUserId=%s, startAt=%s, dueAt=%s, calendarSyncEnabled=%s",
                task.getTitle(),
                task.getDescription() != null ? task.getDescription() : "null",
                task.getStatus().name(),
                task.getAssignee() != null ? task.getAssignee().getId() : "null",
                task.getStartAt() != null ? task.getStartAt().toString() : "null",
                task.getDueAt() != null ? task.getDueAt().toString() : "null",
                task.getCalendarSyncEnabled());
    }
}
