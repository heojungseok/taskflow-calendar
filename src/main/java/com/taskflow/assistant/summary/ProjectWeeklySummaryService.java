package com.taskflow.assistant.summary;

import com.taskflow.assistant.summary.cache.WeeklySummaryCacheService;
import com.taskflow.assistant.summary.dto.WeeklySummaryCacheStatus;
import com.taskflow.assistant.summary.dto.WeeklySummaryResponse;
import com.taskflow.assistant.summary.dto.WeeklySummaryResult;
import com.taskflow.assistant.summary.dto.WeeklySummarySectionResponse;
import com.taskflow.assistant.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.assistant.summary.exception.WeeklySummaryGenerationException;
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
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
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

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final WeeklySummaryGenerator weeklySummaryGenerator;
    private final TaskSyncStateResolver taskSyncStateResolver;
    private final WeeklySummaryCacheService weeklySummaryCacheService;
    private final GeminiSummaryProperties geminiProperties;
    private final UserRepository userRepository;
    private final Clock clock;
    private final SummaryTaskSelector taskSelector = new SummaryTaskSelector();

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
                .sorted(taskSelector.taskPriorityComparator(today))
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
        String fingerprint =
                WeeklySummaryCacheKeys.summaryFingerprint(project, weekStart, weekEnd, prioritizedTasks, modelName);
        String exactCacheKey =
                WeeklySummaryCacheKeys.exactCacheKey(projectId, weekStart, weekEnd, modelName, fingerprint);
        String latestCacheKey = WeeklySummaryCacheKeys.latestCacheKey(projectId, weekStart, weekEnd, modelName);

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
        List<TaskSyncSnapshot> included = taskSelector.selectIncludedTasks(tasks, bucket);
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
        List<TaskSyncSnapshot> syncedIncludedTasks =
                taskSelector.selectIncludedTasks(syncedTasks, SummaryBucket.SYNCED);
        List<TaskSyncSnapshot> unsyncedIncludedTasks =
                taskSelector.selectIncludedTasks(unsyncedTasks, SummaryBucket.UNSYNCED);

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
}
