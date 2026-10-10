package com.taskflow.assistant.summary;

import com.taskflow.assistant.summary.cache.WeeklySummaryCacheService;
import com.taskflow.assistant.summary.dto.WeeklySummaryCacheStatus;
import com.taskflow.assistant.summary.dto.WeeklySummaryResponse;
import com.taskflow.assistant.summary.dto.WeeklySummaryResult;
import com.taskflow.assistant.summary.dto.WeeklySummarySectionResponse;
import com.taskflow.assistant.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.assistant.summary.exception.WeeklySummaryGenerationException;
import com.taskflow.assistant.summary.generator.SummaryPromptTaskSupport;
import com.taskflow.assistant.summary.generator.WeeklySummaryGenerator;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.calendarsync.outbox.TaskSyncStateResolver;
import com.taskflow.identity.security.SecurityContextHelper;
import com.taskflow.identity.user.Provider;
import com.taskflow.identity.user.UserRepository;
import com.taskflow.planning.project.Project;
import com.taskflow.planning.project.ProjectRepository;
import com.taskflow.planning.project.exception.ProjectNotFoundException;
import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskRepository;
import com.taskflow.planning.task.TaskStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectWeeklySummaryService {

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

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final WeeklySummaryGenerator weeklySummaryGenerator;
    private final TaskSyncStateResolver taskSyncStateResolver;
    private final WeeklySummaryCacheService weeklySummaryCacheService;
    private final GeminiSummaryProperties geminiProperties;
    private final UserRepository userRepository;
    private final Clock clock;
    private final SummaryPromptTaskSupport promptTaskSupport = new SummaryPromptTaskSupport();

    public WeeklySummaryResponse generateWeeklySummary(Long projectId) {
        return generateWeeklySummary(projectId, false);
    }

    public WeeklySummaryResponse generateWeeklySummary(Long projectId, boolean forceLive) {
        Long userId = SecurityContextHelper.getCurrentUserId();
        Project project = projectRepository
                .findByIdAndOwnerUserId(projectId, userId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        List<Task> allTasks = taskRepository.findAllByProjectIdAndDeletedFalse(projectId);
        LocalDate today = LocalDate.now(clock);
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        Instant generatedAt = Instant.now();
        List<TaskSyncSnapshot> prioritizedTasks = taskSyncStateResolver.resolveAll(allTasks).stream()
                .sorted(taskPriorityComparator(today))
                .collect(Collectors.toList());
        List<TaskSyncSnapshot> syncedTasks = prioritizedTasks.stream()
                .filter(snapshot -> snapshot.getSyncState().isSynced())
                .collect(Collectors.toList());
        List<TaskSyncSnapshot> unsyncedTasks = prioritizedTasks.stream()
                .filter(snapshot -> !snapshot.getSyncState().isSynced())
                .collect(Collectors.toList());

        if (userRepository
                .findById(userId)
                .map(user -> user.getProvider() == Provider.DEMO)
                .orElse(false)) {
            return buildDemoResponse(project, weekStart, weekEnd, generatedAt, syncedTasks, unsyncedTasks);
        }

        String modelName = geminiProperties.getModel();
        String fingerprint = summaryFingerprint(project, weekStart, weekEnd, prioritizedTasks, modelName);
        String exactCacheKey = exactCacheKey(projectId, weekStart, weekEnd, modelName, fingerprint);
        String latestCacheKey = latestCacheKey(projectId, weekStart, weekEnd, modelName);

        if (!forceLive && weeklySummaryCacheService.isEnabled()) {
            WeeklySummaryResponse cached = weeklySummaryCacheService
                    .find(exactCacheKey)
                    .map(response -> response.withCacheStatus(WeeklySummaryCacheStatus.CACHE_HIT))
                    .orElse(null);
            if (cached != null) {
                log.info(
                        "Weekly summary cache hit. projectId={}, weekStart={}, cacheKey={}",
                        projectId,
                        weekStart,
                        exactCacheKey);
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
                    WeeklySummaryCacheStatus.LIVE);

            if (weeklySummaryCacheService.isEnabled()) {
                weeklySummaryCacheService.save(exactCacheKey, latestCacheKey, liveResponse);
                log.info(
                        "Weekly summary cache stored. projectId={}, weekStart={}, cacheKey={}",
                        projectId,
                        weekStart,
                        exactCacheKey);
            }

            return liveResponse;
        } catch (WeeklySummaryGenerationException e) {
            if (!forceLive && weeklySummaryCacheService.isEnabled() && e.isFallbackEligible()) {
                WeeklySummaryResponse fallback = readLatestCachedResponse(latestCacheKey);
                if (fallback != null) {
                    log.warn(
                            "Weekly summary stale fallback served. projectId={}, errorCode={}, latestCacheKey={}",
                            projectId,
                            e.getErrorCode().getCode(),
                            latestCacheKey);
                    return fallback.withCacheStatus(WeeklySummaryCacheStatus.STALE_FALLBACK);
                }
            }
            throw e;
        }
    }

    private WeeklySummaryResponse buildDemoResponse(
            Project project,
            LocalDate weekStart,
            LocalDate weekEnd,
            Instant generatedAt,
            List<TaskSyncSnapshot> syncedTasks,
            List<TaskSyncSnapshot> unsyncedTasks) {
        return WeeklySummaryResponse.of(
                project,
                weekStart,
                weekEnd,
                generatedAt,
                WeeklySummaryCacheStatus.DEMO_LOCAL,
                syncedTasks.size() + unsyncedTasks.size(),
                syncedTasks.size(),
                unsyncedTasks.size(),
                demoSection(syncedTasks, SummaryBucket.SYNCED),
                demoSection(unsyncedTasks, SummaryBucket.UNSYNCED));
    }

    private WeeklySummarySectionResponse demoSection(List<TaskSyncSnapshot> tasks, SummaryBucket bucket) {
        if (tasks.isEmpty()) {
            return WeeklySummarySectionResponse.of(
                    0, 0, WeeklySummaryResult.empty(bucket.getEmptySummary(), bucket.getEmptyNextActions()));
        }
        List<TaskSyncSnapshot> included = selectIncludedTasks(tasks, bucket);
        return WeeklySummarySectionResponse.of(
                tasks.size(),
                included.size(),
                WeeklySummaryResult.of(
                        "데모 로컬 요약: " + tasks.size() + "개의 Task가 있습니다.",
                        List.of(),
                        List.of(),
                        List.of("우선순위가 높은 Task부터 진행해보세요."),
                        "demo-local"));
    }

    private WeeklySummaryResponse buildResponse(
            Project project,
            LocalDate weekStart,
            LocalDate weekEnd,
            Instant generatedAt,
            List<TaskSyncSnapshot> syncedTasks,
            List<TaskSyncSnapshot> unsyncedTasks,
            WeeklySummaryCacheStatus cacheStatus) {
        List<TaskSyncSnapshot> syncedIncludedTasks = selectIncludedTasks(syncedTasks, SummaryBucket.SYNCED);
        List<TaskSyncSnapshot> unsyncedIncludedTasks = selectIncludedTasks(unsyncedTasks, SummaryBucket.UNSYNCED);

        WeeklySummarySectionsResult generatedSections = shouldCallGenerator(syncedTasks, unsyncedTasks)
                ? weeklySummaryGenerator.generate(
                        project,
                        syncedIncludedTasks,
                        syncedTasks.size(),
                        unsyncedIncludedTasks,
                        unsyncedTasks.size(),
                        weekStart,
                        weekEnd)
                : WeeklySummarySectionsResult.of(null, null);

        WeeklySummarySectionResponse synced =
                buildSection(syncedTasks, syncedIncludedTasks, SummaryBucket.SYNCED, generatedSections.getSynced());
        WeeklySummarySectionResponse unsynced = buildSection(
                unsyncedTasks, unsyncedIncludedTasks, SummaryBucket.UNSYNCED, generatedSections.getUnsynced());

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
                unsynced);
    }

    private boolean shouldCallGenerator(List<TaskSyncSnapshot> syncedTasks, List<TaskSyncSnapshot> unsyncedTasks) {
        return !syncedTasks.isEmpty() || !unsyncedTasks.isEmpty();
    }

    private List<TaskSyncSnapshot> selectIncludedTasks(List<TaskSyncSnapshot> tasks, SummaryBucket bucket) {
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

    private WeeklySummaryResponse readLatestCachedResponse(String latestCacheKey) {
        return weeklySummaryCacheService.find(latestCacheKey).orElse(null);
    }

    private WeeklySummarySectionResponse buildSection(
            List<TaskSyncSnapshot> allTasks,
            List<TaskSyncSnapshot> includedTasks,
            SummaryBucket bucket,
            WeeklySummaryResult result) {
        if (allTasks.isEmpty()) {
            return WeeklySummarySectionResponse.of(
                    0, 0, WeeklySummaryResult.empty(bucket.getEmptySummary(), bucket.getEmptyNextActions()));
        }

        return WeeklySummarySectionResponse.of(
                allTasks.size(),
                includedTasks.size(),
                Objects.requireNonNull(result, "Weekly summary result must not be null for non-empty section"));
    }

    private Comparator<TaskSyncSnapshot> taskPriorityComparator(LocalDate today) {
        return Comparator.comparingInt((TaskSyncSnapshot snapshot) -> taskPriority(snapshot.getTask(), today))
                .reversed()
                .thenComparing(
                        snapshot -> effectiveEventEndAt(snapshot.getTask()),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                        snapshot -> snapshot.getTask().getCreatedAt(), Comparator.nullsLast(Comparator.naturalOrder()));
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

    private String summaryFingerprint(
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

    private String exactCacheKey(
            Long projectId, LocalDate weekStart, LocalDate weekEnd, String modelName, String fingerprint) {
        return "weekly-summary:v2:exact:" + projectId + ":" + weekStart + ":" + weekEnd + ":" + modelName + ":"
                + fingerprint;
    }

    private String latestCacheKey(Long projectId, LocalDate weekStart, LocalDate weekEnd, String modelName) {
        return "weekly-summary:v2:latest:" + projectId + ":" + weekStart + ":" + weekEnd + ":" + modelName;
    }
}
