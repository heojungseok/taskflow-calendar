package com.taskflow.assistant.summary;

import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.planning.project.Project;
import com.taskflow.planning.task.Task;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 주간 요약 캐시 키. 문자열이 한 글자라도 바뀌면 기존 캐시가 모두 무효가 된다. */
final class WeeklySummaryCacheKeys {

    private WeeklySummaryCacheKeys() {}

    static String summaryFingerprint(
            Project project,
            LocalDate weekStart,
            LocalDate weekEnd,
            List<TaskSyncSnapshot> prioritizedTasks,
            String modelName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, project.getId());
            updateDigest(digest, weekStart);
            updateDigest(digest, weekEnd);
            updateDigest(digest, modelName);

            for (TaskSyncSnapshot snapshot : prioritizedTasks) {
                Task task = snapshot.getTask();
                updateDigest(digest, task.getId());
                updateDigest(digest, task.getTitle());
                updateDigest(digest, task.getDescription());
                updateDigest(digest, task.getStatus());
                updateDigest(digest, task.getStartAt());
                updateDigest(digest, task.getDueAt());
                updateDigest(digest, task.getUpdatedAt());
                updateDigest(digest, task.getCalendarSyncEnabled());
                updateDigest(digest, task.getCalendarEventId());
                updateDigest(digest, snapshot.getSyncState());
                updateDigest(digest, snapshot.getLatestOutboxStatus());
                updateDigest(digest, snapshot.getLatestOutboxOpType());
                updateDigest(digest, snapshot.getLatestOutboxError());
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void updateDigest(MessageDigest digest, Object value) {
        String normalized = Objects.toString(value, "<null>");
        digest.update(normalized.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    static String exactCacheKey(
            Long projectId, LocalDate weekStart, LocalDate weekEnd, String modelName, String fingerprint) {
        return "weekly-summary:v2:exact:" + projectId + ":" + weekStart + ":" + weekEnd + ":" + modelName + ":"
                + fingerprint;
    }

    static String latestCacheKey(Long projectId, LocalDate weekStart, LocalDate weekEnd, String modelName) {
        return "weekly-summary:v2:latest:" + projectId + ":" + weekStart + ":" + weekEnd + ":" + modelName;
    }
}
