package com.taskflow.assistant.search;

import com.taskflow.assistant.search.dto.ProjectTaskSearchResponse;
import com.taskflow.assistant.search.dto.RelatedProjectSearchResultResponse;
import com.taskflow.assistant.search.dto.SearchIntentResponse;
import com.taskflow.assistant.search.dto.TaskSearchResultItemResponse;
import com.taskflow.assistant.search.generator.TaskSearchIntentParser;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.calendarsync.outbox.TaskSyncStateResolver;
import com.taskflow.identity.security.SecurityContextHelper;
import com.taskflow.identity.user.Provider;
import com.taskflow.identity.user.UserRepository;
import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectTaskSearchService {

    private static final Logger log = LoggerFactory.getLogger(ProjectTaskSearchService.class);

    private static final int TASK_RESULT_LIMIT = 6;
    private static final int PROJECT_RESULT_LIMIT = 3;

    private final TaskRepository taskRepository;
    private final TaskSyncStateResolver taskSyncStateResolver;
    private final TaskSearchIntentParser taskSearchIntentParser;
    private final TaskSearchEmbeddingService taskSearchEmbeddingService;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public ProjectTaskSearchResponse search(String query) {
        Long userId = SecurityContextHelper.getCurrentUserId();
        boolean demo = userRepository
                .findById(userId)
                .map(user -> user.getProvider() == Provider.DEMO)
                .orElse(false);
        SearchIntent intent = SearchIntentNormalizer.normalizeIntent(
                demo ? SearchIntentNormalizer.localIntent(query) : taskSearchIntentParser.parse(query));
        log.info(
                "Task search parsed intent. queryType={}, targetType={}, domainType={}, mainAction={}, genericCompanionRequired={}, relationPolicy={}, timeIntent={}, priorityIntent={}, syncIntent={}, overallConfidence={}",
                intent.getQueryType(),
                intent.getTargetType(),
                intent.getDomainType(),
                intent.getMainAction(),
                intent.isGenericCompanionRequired(),
                intent.getRelationPolicy(),
                intent.getTimeIntent(),
                intent.getPriorityIntent(),
                intent.getSyncIntent(),
                intent.getOverallConfidence());

        if (SearchIntentNormalizer.shouldFallback(intent)) {
            log.info(
                    "Task search fallback. topicWeak={}, mainActionWeak={}, genericCompanionRequired={}, relationPolicy={}",
                    !intent.hasUsefulTopicTerms(),
                    !intent.hasUsefulMainAction(),
                    intent.isGenericCompanionRequired(),
                    intent.getRelationPolicy());
            return ProjectTaskSearchResponse.of(
                    query,
                    true,
                    demo ? SemanticSearchStatus.DISABLED : taskSearchEmbeddingService.semanticStatus(),
                    SearchIntentResponse.from(intent),
                    List.of(),
                    List.of(),
                    truncateSuggestions(intent.getSuggestedQueries()));
        }

        List<Task> tasks = taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(userId);
        if (!demo) {
            taskSearchEmbeddingService.ensureEmbeddings(tasks);
        }
        SemanticSearchResult semanticResult = demo
                ? new SemanticSearchResult(Map.of(), SemanticSearchStatus.DISABLED)
                : taskSearchEmbeddingService.searchSimilarities(intent);
        Map<Long, Double> semanticSimilarities = semanticResult.similarities();

        LocalDateTime now = LocalDateTime.now(clock);
        List<ScoredTask> rankedTasks = taskSyncStateResolver.resolveAll(tasks).stream()
                .flatMap(snapshot -> TaskSearchScorer.score(
                                snapshot,
                                intent,
                                semanticSimilarities.getOrDefault(
                                        snapshot.getTask().getId(), 0.0d),
                                now)
                        .stream()
                        .mapToObj(total -> new ScoredTask(snapshot, total)))
                .sorted(Comparator.comparingInt(ScoredTask::totalScore)
                        .reversed()
                        .thenComparing(
                                scored -> scored.snapshot().getTask().getDueAt(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(
                                scored -> scored.snapshot().getTask().getUpdatedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(
                                scored -> scored.snapshot().getTask().getId(),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(TASK_RESULT_LIMIT)
                .collect(Collectors.toList());

        logTopCandidates(rankedTasks);

        List<TaskSearchResultItemResponse> taskResults = rankedTasks.stream()
                .map(scored -> {
                    Task task = scored.snapshot().getTask();
                    return TaskSearchResultItemResponse.of(
                            task.getId(),
                            task.getProject().getId(),
                            task.getProject().getName(),
                            task.getTitle(),
                            task.getStatus(),
                            task.getDueAt(),
                            task.getCalendarSyncEnabled(),
                            task.getCalendarEventId(),
                            scored.snapshot().getSyncState(),
                            scored.totalScore());
                })
                .collect(Collectors.toList());

        List<RelatedProjectSearchResultResponse> relatedProjects = buildRelatedProjects(rankedTasks);

        return ProjectTaskSearchResponse.of(
                query,
                false,
                // 검색을 실제로 수행한 그 호출이 돌려준 상태다. 나중에 따로 물으면
                // 임베딩 호출 실패(429 등)를 놓친다.
                semanticResult.status(),
                SearchIntentResponse.from(intent),
                taskResults,
                relatedProjects,
                List.of());
    }

    private List<String> truncateSuggestions(List<String> suggestedQueries) {
        if (suggestedQueries.isEmpty()) {
            return List.of("이번 주 마감 일정", "캘린더 반영 안 된 일정", "차단된 작업");
        }
        return suggestedQueries.stream().limit(3).collect(Collectors.toList());
    }

    private List<RelatedProjectSearchResultResponse> buildRelatedProjects(List<ScoredTask> rankedTasks) {
        Map<Long, List<ScoredTask>> grouped = rankedTasks.stream()
                .collect(Collectors.groupingBy(
                        scored -> scored.snapshot().getTask().getProject().getId()));

        if (grouped.size() <= 1) {
            return List.of();
        }

        return grouped.values().stream()
                .map(scores -> {
                    Task representative = scores.get(0).snapshot().getTask();
                    int score = scores.stream()
                            .limit(3)
                            .mapToInt(ScoredTask::totalScore)
                            .sum();
                    return RelatedProjectSearchResultResponse.of(
                            representative.getProject().getId(),
                            representative.getProject().getName(),
                            scores.size(),
                            score);
                })
                .sorted(Comparator.comparingInt(RelatedProjectSearchResultResponse::getScore)
                        .reversed())
                .limit(PROJECT_RESULT_LIMIT)
                .collect(Collectors.toList());
    }

    private void logTopCandidates(List<ScoredTask> rankedTasks) {
        if (rankedTasks.isEmpty()) {
            log.info("Task search produced no ranked candidates");
            return;
        }
        log.info("Task search ranked candidates. count={}", rankedTasks.size());
    }

    private record ScoredTask(TaskSyncSnapshot snapshot, int totalScore) {}
}
