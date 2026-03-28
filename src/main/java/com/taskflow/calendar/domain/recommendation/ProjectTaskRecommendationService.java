package com.taskflow.calendar.domain.recommendation;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.project.exception.ProjectNotFoundException;
import com.taskflow.calendar.domain.recommendation.cache.TaskRecommendationCacheService;
import com.taskflow.calendar.domain.recommendation.dto.ProjectTaskRecommendationItemResponse;
import com.taskflow.calendar.domain.recommendation.dto.ProjectTaskRecommendationResponse;
import com.taskflow.calendar.domain.recommendation.dto.TaskRecommendationCacheStatus;
import com.taskflow.calendar.domain.recommendation.generator.TaskRecommendationGenerationResult;
import com.taskflow.calendar.domain.recommendation.generator.TaskRecommendationGenerator;
import com.taskflow.calendar.domain.recommendation.generator.TaskRecommendationItemResult;
import com.taskflow.calendar.domain.summary.SummaryTaskSnapshot;
import com.taskflow.calendar.domain.summary.TaskSyncState;
import com.taskflow.calendar.domain.summary.TaskSyncStateResolver;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.config.GeminiRecommendationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectTaskRecommendationService {

    private static final String CACHE_KEY_VERSION = "v4";
    private static final int MAX_CANDIDATE_COUNT = 8;
    private static final int MAX_RECOMMENDATION_COUNT = 5;
    private static final int DEFAULT_EVENT_DURATION_HOURS = 1;
    private static final int RECENT_UPDATE_HOURS = 24;

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final TaskSyncStateResolver taskSyncStateResolver;
    private final TaskRecommendationGenerator taskRecommendationGenerator;
    private final TaskRecommendationCacheService taskRecommendationCacheService;
    private final GeminiRecommendationProperties geminiProperties;

    public ProjectTaskRecommendationResponse getRecommendations(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        LocalDate today = LocalDate.now();
        LocalDateTime generatedAt = LocalDateTime.now();
        List<Task> eligibleTasks = taskRepository.findAllByProjectIdAndDeletedFalse(projectId).stream()
                .filter(task -> task.getStatus() != TaskStatus.DONE)
                .collect(Collectors.toList());

        if (eligibleTasks.isEmpty()) {
            return ProjectTaskRecommendationResponse.of(
                    project,
                    generatedAt,
                    TaskRecommendationCacheStatus.LIVE,
                    0,
                    0,
                    List.of()
            );
        }

        List<SummaryTaskSnapshot> prioritizedSnapshots = eligibleTasks.stream()
                .map(taskSyncStateResolver::resolve)
                .sorted(candidateComparator(today))
                .collect(Collectors.toList());

        int recommendationCount = Math.min(
                MAX_RECOMMENDATION_COUNT,
                Math.max(1, (int) Math.ceil(prioritizedSnapshots.size() * 0.3d))
        );
        List<SummaryTaskSnapshot> candidates = prioritizedSnapshots.stream()
                .limit(MAX_CANDIDATE_COUNT)
                .collect(Collectors.toList());

        String cacheKey = cacheKey(project, candidates, recommendationCount, geminiProperties.getModel());
        if (taskRecommendationCacheService.isEnabled()) {
            ProjectTaskRecommendationResponse cached = taskRecommendationCacheService.find(cacheKey)
                    .map(response -> response.withCacheStatus(TaskRecommendationCacheStatus.CACHE_HIT))
                    .orElse(null);
            if (cached != null) {
                return cached;
            }
        }

        TaskRecommendationGenerationResult generated = taskRecommendationGenerator.generate(
                project,
                candidates,
                recommendationCount,
                today
        );
        ProjectTaskRecommendationResponse response = buildResponse(
                project,
                generatedAt,
                prioritizedSnapshots.size(),
                candidates,
                generated.getItems()
        );

        if (taskRecommendationCacheService.isEnabled()) {
            taskRecommendationCacheService.save(cacheKey, response);
        }

        return response;
    }

    private ProjectTaskRecommendationResponse buildResponse(Project project,
                                                            LocalDateTime generatedAt,
                                                            int totalEligibleTaskCount,
                                                            List<SummaryTaskSnapshot> candidates,
                                                            List<TaskRecommendationItemResult> generatedItems) {
        Map<Long, SummaryTaskSnapshot> candidateMap = new LinkedHashMap<>();
        Map<Long, Integer> candidateScoreMap = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (SummaryTaskSnapshot candidate : candidates) {
            Long taskId = candidate.getTask().getId();
            candidateMap.put(taskId, candidate);
            candidateScoreMap.put(taskId, candidateScore(candidate, today));
        }

        List<ProjectTaskRecommendationItemResponse> items = new ArrayList<>();
        int rank = 1;
        for (TaskRecommendationItemResult generatedItem : generatedItems) {
            SummaryTaskSnapshot snapshot = candidateMap.get(generatedItem.getTaskId());
            if (snapshot == null) {
                continue;
            }

            Task task = snapshot.getTask();
            items.add(ProjectTaskRecommendationItemResponse.of(
                    task.getId(),
                    rank++,
                    candidateScoreMap.getOrDefault(task.getId(), 0),
                    task.getTitle(),
                    task.getStatus(),
                    task.getDueAt(),
                    task.getCalendarSyncEnabled(),
                    task.getCalendarEventId(),
                    snapshot.getSyncState(),
                    generatedItem.getPrimaryTag(),
                    generatedItem.getSecondaryTag(),
                    generatedItem.getReason()
            ));
        }

        return ProjectTaskRecommendationResponse.of(
                project,
                generatedAt,
                TaskRecommendationCacheStatus.LIVE,
                totalEligibleTaskCount,
                candidates.size(),
                items
        );
    }

    private Comparator<SummaryTaskSnapshot> candidateComparator(LocalDate today) {
        return Comparator
                .comparingInt((SummaryTaskSnapshot snapshot) -> candidateScore(snapshot, today))
                .reversed()
                .thenComparing(snapshot -> snapshot.getTask().getDueAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(snapshot -> snapshot.getTask().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(snapshot -> snapshot.getTask().getId(), Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private int candidateScore(SummaryTaskSnapshot snapshot, LocalDate today) {
        Task task = snapshot.getTask();
        int score = 0;

        switch (task.getStatus()) {
            case BLOCKED:
                score += 110;
                break;
            case IN_PROGRESS:
                score += 60;
                break;
            case REQUESTED:
                score += 40;
                break;
            default:
                break;
        }

        score += dueUrgencyScore(task, today);
        score += syncRiskScore(snapshot);
        score += recentActivityScore(task);
        score += descriptionPresenceScore(task);
        return score;
    }

    private int dueUrgencyScore(Task task, LocalDate today) {
        if (task.getDueAt() == null) {
            return 0;
        }

        LocalDate dueDate = task.getDueAt().toLocalDate();
        if (dueDate.isBefore(today)) {
            return 120;
        }
        if (!dueDate.isAfter(today.plusDays(1))) {
            return 95;
        }
        if (!dueDate.isAfter(today.plusDays(3))) {
            return 70;
        }
        if (!dueDate.isAfter(today.plusDays(7))) {
            return 45;
        }
        return 0;
    }

    private int syncRiskScore(SummaryTaskSnapshot snapshot) {
        int score = 0;
        TaskSyncState syncState = snapshot.getSyncState();
        if (syncState == TaskSyncState.FAILED_SYNC || syncState == TaskSyncState.DELETE_FAILED) {
            score += 85;
        } else if (syncState == TaskSyncState.PENDING_SYNC || syncState == TaskSyncState.DELETE_PENDING) {
            score += 60;
        }

        if (snapshot.getLatestOutboxStatus() != null) {
            switch (snapshot.getLatestOutboxStatus()) {
                case FAILED:
                    score += 45;
                    break;
                case PENDING:
                case PROCESSING:
                    score += 25;
                    break;
                default:
                    break;
            }
        }

        return score;
    }

    private int recentActivityScore(Task task) {
        if (task.getUpdatedAt() == null) {
            return 0;
        }
        if (task.getUpdatedAt().isAfter(LocalDateTime.now().minusHours(RECENT_UPDATE_HOURS))) {
            return 20;
        }
        return 0;
    }

    private int descriptionPresenceScore(Task task) {
        int score = 0;
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            score += 10;
        }

        LocalDateTime effectiveEventStartAt = task.getStartAt() != null
                ? task.getStartAt()
                : (task.getDueAt() != null ? task.getDueAt().minusHours(DEFAULT_EVENT_DURATION_HOURS) : null);
        if (effectiveEventStartAt != null && task.getDueAt() != null) {
            score += 8;
        }
        return score;
    }

    private String cacheKey(Project project,
                            List<SummaryTaskSnapshot> candidates,
                            int recommendationCount,
                            String modelName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, project.getId());
            updateDigest(digest, modelName);
            updateDigest(digest, recommendationCount);

            for (SummaryTaskSnapshot snapshot : candidates) {
                Task task = snapshot.getTask();
                updateDigest(digest, task.getId());
                updateDigest(digest, task.getTitle());
                updateDigest(digest, task.getDescription());
                updateDigest(digest, task.getStatus());
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
            return "task-recommendation:" + CACHE_KEY_VERSION + ":" + project.getId() + ":" + modelName + ":" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void updateDigest(MessageDigest digest, Object value) {
        String normalized = Objects.toString(value, "<null>");
        digest.update(normalized.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
