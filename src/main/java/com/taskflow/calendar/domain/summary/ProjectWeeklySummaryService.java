package com.taskflow.calendar.domain.summary;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.project.exception.ProjectNotFoundException;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryCacheStatus;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummarySectionResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResult;
import com.taskflow.calendar.domain.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.config.GeminiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectWeeklySummaryService {

    private static final int MAX_TASKS_PER_SECTION = 15;
    private static final int DEFAULT_EVENT_DURATION_HOURS = 1;
    private static final int RECENT_UPDATE_HOURS = 24;
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
    private final WeeklySummaryCacheService weeklySummaryCacheService;
    private final GeminiProperties geminiProperties;

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

        String modelName = geminiProperties.getModel();
        String fingerprint = summaryFingerprint(project, weekStart, weekEnd, prioritizedTasks, modelName);
        String exactCacheKey = exactCacheKey(projectId, weekStart, weekEnd, modelName, fingerprint);
        String latestCacheKey = latestCacheKey(projectId, weekStart, weekEnd, modelName);

        if (weeklySummaryCacheService.isEnabled()) {
            WeeklySummaryResponse cached = weeklySummaryCacheService.find(exactCacheKey)
                    .map(response -> response.withCacheStatus(WeeklySummaryCacheStatus.CACHE_HIT))
                    .orElse(null);
            if (cached != null) {
                log.info("Weekly summary cache hit. projectId={}, weekStart={}, cacheKey={}",
                        projectId, weekStart, exactCacheKey);
                return cached;
            }
        }

        try {
            WeeklySummaryResponse liveResponse = buildResponse(
                    project,
                    weekStart,
                    weekEnd,
                    generatedAt,
                    syncedTasks,
                    unsyncedTasks,
                    WeeklySummaryCacheStatus.LIVE
            );

            if (weeklySummaryCacheService.isEnabled()) {
                weeklySummaryCacheService.save(exactCacheKey, latestCacheKey, liveResponse);
                log.info("Weekly summary cache stored. projectId={}, weekStart={}, cacheKey={}",
                        projectId, weekStart, exactCacheKey);
            }

            return liveResponse;
        } catch (WeeklySummaryGenerationException e) {
            if (weeklySummaryCacheService.isEnabled() && e.isFallbackEligible()) {
                WeeklySummaryResponse fallback = readLatestCachedResponse(latestCacheKey);
                if (fallback != null) {
                    log.warn("Weekly summary stale fallback served. projectId={}, errorCode={}, latestCacheKey={}",
                            projectId, e.getErrorCode().getCode(), latestCacheKey);
                    return fallback.withCacheStatus(WeeklySummaryCacheStatus.STALE_FALLBACK);
                }
            }
            throw e;
        }
    }

    private WeeklySummaryResponse buildResponse(Project project,
                                                LocalDate weekStart,
                                                LocalDate weekEnd,
                                                LocalDateTime generatedAt,
                                                List<SummaryTaskSnapshot> syncedTasks,
                                                List<SummaryTaskSnapshot> unsyncedTasks,
                                                WeeklySummaryCacheStatus cacheStatus) {
        List<SummaryTaskSnapshot> syncedIncludedTasks = syncedTasks.stream()
                .limit(MAX_TASKS_PER_SECTION)
                .collect(Collectors.toList());
        List<SummaryTaskSnapshot> unsyncedIncludedTasks = unsyncedTasks.stream()
                .limit(MAX_TASKS_PER_SECTION)
                .collect(Collectors.toList());

        WeeklySummarySectionsResult generatedSections = shouldCallGenerator(syncedTasks, unsyncedTasks)
                ? weeklySummaryGenerator.generate(
                project,
                syncedIncludedTasks,
                syncedTasks.size(),
                unsyncedIncludedTasks,
                unsyncedTasks.size(),
                weekStart,
                weekEnd
        )
                : WeeklySummarySectionsResult.of(null, null);

        WeeklySummarySectionResponse synced = buildSection(
                syncedTasks,
                syncedIncludedTasks,
                SummaryBucket.SYNCED,
                generatedSections.getSynced()
        );
        WeeklySummarySectionResponse unsynced = buildSection(
                unsyncedTasks,
                unsyncedIncludedTasks,
                SummaryBucket.UNSYNCED,
                generatedSections.getUnsynced()
        );

        return WeeklySummaryResponse.of(
                project,
                weekStart,
                weekEnd,
                generatedAt,
                cacheStatus,
                syncedTasks.size() + unsyncedTasks.size(),
                syncedTasks.size(),
                unsyncedTasks.size(),
                synced,
                unsynced
        );
    }

    private boolean shouldCallGenerator(List<SummaryTaskSnapshot> syncedTasks, List<SummaryTaskSnapshot> unsyncedTasks) {
        return !syncedTasks.isEmpty() || !unsyncedTasks.isEmpty();
    }

    private WeeklySummaryResponse readLatestCachedResponse(String latestCacheKey) {
        return weeklySummaryCacheService.find(latestCacheKey).orElse(null);
    }

    private WeeklySummarySectionResponse buildSection(List<SummaryTaskSnapshot> allTasks,
                                                      List<SummaryTaskSnapshot> includedTasks,
                                                      SummaryBucket bucket,
                                                      WeeklySummaryResult result) {
        if (allTasks.isEmpty()) {
            return WeeklySummarySectionResponse.of(
                    0,
                    0,
                    WeeklySummaryResult.empty(bucket.getEmptySummary(), bucket.getEmptyNextActions())
            );
        }

        return WeeklySummarySectionResponse.of(
                allTasks.size(),
                includedTasks.size(),
                Objects.requireNonNull(result, "Weekly summary result must not be null for non-empty section")
        );
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

    private int recentlyUpdatedPriority(Task task) {
        if (task.getUpdatedAt() == null) {
            return 0;
        }
        if (task.getDescription() == null || task.getDescription().isBlank()) {
            return 0;
        }

        LocalDateTime threshold = LocalDateTime.now().minusHours(RECENT_UPDATE_HOURS);
        if (task.getUpdatedAt().isAfter(threshold)) {
            return 25;
        }
        return 0;
    }

    private String summaryFingerprint(Project project,
                                      LocalDate weekStart,
                                      LocalDate weekEnd,
                                      List<SummaryTaskSnapshot> prioritizedTasks,
                                      String modelName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, project.getId());
            updateDigest(digest, weekStart);
            updateDigest(digest, weekEnd);
            updateDigest(digest, modelName);

            for (SummaryTaskSnapshot snapshot : prioritizedTasks) {
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

            byte[] hashed = digest.digest();
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void updateDigest(MessageDigest digest, Object value) {
        String normalized = Objects.toString(value, "<null>");
        digest.update(normalized.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private String exactCacheKey(Long projectId,
                                 LocalDate weekStart,
                                 LocalDate weekEnd,
                                 String modelName,
                                 String fingerprint) {
        return "weekly-summary:v1:exact:" + projectId + ":" + weekStart + ":" + weekEnd + ":" + modelName + ":" + fingerprint;
    }

    private String latestCacheKey(Long projectId,
                                  LocalDate weekStart,
                                  LocalDate weekEnd,
                                  String modelName) {
        return "weekly-summary:v1:latest:" + projectId + ":" + weekStart + ":" + weekEnd + ":" + modelName;
    }

}
