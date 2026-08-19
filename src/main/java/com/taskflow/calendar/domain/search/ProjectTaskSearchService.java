package com.taskflow.calendar.domain.search;

import com.taskflow.calendar.domain.search.dto.ProjectTaskSearchResponse;
import com.taskflow.calendar.domain.search.dto.RelatedProjectSearchResultResponse;
import com.taskflow.calendar.domain.search.dto.SearchIntentResponse;
import com.taskflow.calendar.domain.search.dto.TaskSearchResultItemResponse;
import com.taskflow.calendar.domain.search.generator.TaskSearchIntentParser;
import com.taskflow.calendar.domain.summary.SummaryTaskSnapshot;
import com.taskflow.calendar.domain.summary.TaskSyncState;
import com.taskflow.calendar.domain.summary.TaskSyncStateResolver;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.security.SecurityContextHelper;
import com.taskflow.calendar.domain.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectTaskSearchService {

    private static final Logger log = LoggerFactory.getLogger(ProjectTaskSearchService.class);

    private static final int TASK_RESULT_LIMIT = 6;
    private static final int PROJECT_RESULT_LIMIT = 3;

    private static final int ROLE_ENTITY_WEIGHT = 25;
    private static final int MAIN_ACTION_WEIGHT = 20;
    private static final int SECONDARY_ACTION_WEIGHT = 10;
    private static final int RELATION_WEIGHT = 20;
    private static final int TIME_WEIGHT = 10;
    private static final int DOMAIN_WEIGHT = 5;
    private static final int SEMANTIC_WEIGHT = 20;

    private static final int STATUS_MATCH_BOOST = 6;
    private static final int STATUS_MISMATCH_PENALTY = -6;
    private static final int SYNC_MATCH_BOOST = 6;
    private static final int SYNC_MISMATCH_PENALTY = -6;
    private static final int PRIORITY_MATCH_BOOST = 8;

    private static final Set<String> GENERIC_TERMS = Set.of(
            "일정",
            "작업",
            "업무",
            "할일",
            "할 일",
            "것"
    );

    private static final Set<String> LEISURE_TOPIC_TERMS = Set.of(
            "놀기",
            "놀이",
            "놀",
            "나들이",
            "여가",
            "데이트"
    );

    private static final List<String> LEISURE_TOPIC_EXPANSIONS = List.of(
            "놀기",
            "놀이",
            "놀",
            "나들이",
            "여가",
            "데이트",
            "만남",
            "약속",
            "외출",
            "놀러",
            "소풍",
            "피크닉"
    );

    private static final Map<String, List<String>> TOPIC_PHRASE_EXPANSIONS = Map.of(
            "화상 회의", List.of("화상 회의", "온라인 회의", "줌 회의", "줌 미팅", "구글 밋", "구글밋", "google meet", "zoom meeting")
    );

    private static final List<String> PARTICIPANT_HINT_TERMS = List.of(
            "친구", "가족", "엄마", "아빠", "부모", "부모님", "형", "누나", "언니", "오빠", "동생",
            "팀원", "동료", "고객", "선생님", "배우자", "남편", "아내", "아이", "애인"
    );

    private static final List<String> GENERIC_COMPANION_TERMS = List.of(
            "누군가", "누구", "아무개", "상대", "사람"
    );

    private static final List<String> GENERIC_COMPANION_QUERY_CUES = List.of(
            "누군가와", "누군가랑", "누구와", "누구랑", "아무개와", "아무개랑", "같이", "함께", "동행", "데리고", "모시고"
    );

    private static final List<String> TASK_COMPANION_CUES = List.of(
            "같이", "함께", "동행", "데리고", "모시고"
    );

    private static final List<String> LOCATION_HINT_TERMS = List.of(
            "병원", "치과", "약국", "은행", "마트", "주민센터", "관공서", "미용실", "공항", "학교", "회사"
    );

    private static final List<String> LIFE_LOCATION_TERMS = List.of(
            "병원", "치과", "약국", "은행", "마트", "주민센터", "관공서", "미용실"
    );

    private static final List<String> VISIT_QUERY_HINTS = List.of(
            "가는", "가기", "가야", "방문", "들르", "내원", "진료", "검사", "예약"
    );

    private static final List<String> MEET_QUERY_HINTS = List.of(
            "만나", "약속", "같이", "함께", "동행", "놀", "나들이", "데이트"
    );

    private static final Map<SearchActionIntent, List<String>> ACTION_KEYWORDS = Map.of(
            SearchActionIntent.PREPARE, List.of("준비", "체크리스트", "초안", "자료", "발표", "세팅", "작성"),
            SearchActionIntent.SUBMIT, List.of("제출", "업로드", "전송", "신청", "등록"),
            SearchActionIntent.BUY, List.of("구매", "장보기", "주문", "결제", "마트", "사기"),
            SearchActionIntent.VISIT, List.of("방문", "병원", "진료", "검사", "내원", "가야", "가기"),
            SearchActionIntent.MEET, List.of("만나", "약속", "동행", "놀", "놀이", "나들이", "여가", "데이트"),
            SearchActionIntent.ORGANIZE, List.of("정리", "정돈", "분류", "정리하기"),
            SearchActionIntent.FIX, List.of("수정", "해결", "복구", "버그", "장애", "패치", "고치"),
            SearchActionIntent.CHECK, List.of("확인", "검토", "점검", "리뷰", "체크", "팔로업", "follow up")
    );

    private static final Map<SearchDomainType, List<String>> DOMAIN_KEYWORDS = Map.of(
            SearchDomainType.WORK, List.of("배포", "릴리즈", "스프린트", "운영", "개발", "qa", "리뷰", "프로젝트", "업무"),
            SearchDomainType.PERSONAL, List.of("개인", "가족", "친구", "약속", "취미", "개인 일정"),
            SearchDomainType.LIFE, List.of("병원", "진료", "검사", "약", "장보기", "마트", "서류", "청소", "집안", "은행", "예약", "보험")
    );

    private final TaskRepository taskRepository;
    private final TaskSyncStateResolver taskSyncStateResolver;
    private final TaskSearchIntentParser taskSearchIntentParser;
    private final TaskSearchEmbeddingService taskSearchEmbeddingService;

    @Transactional
    public ProjectTaskSearchResponse search(String query) {
        SearchIntent intent = normalizeIntent(taskSearchIntentParser.parse(query));
        log.info("Task search parsed intent. query='{}', queryType={}, targetType={}, domainType={}, mainAction={}, secondaryActions={}, topicTerms={}, participantTerms={}, locationTerms={}, genericCompanionRequired={}, relationPolicy={}, timeIntent={}, priorityIntent={}, statusIntents={}, syncIntent={}, overallConfidence={}",
                query,
                intent.getQueryType(),
                intent.getTargetType(),
                intent.getDomainType(),
                intent.getMainAction(),
                intent.getSecondaryActions(),
                intent.getTopicTerms(),
                intent.getParticipantTerms(),
                intent.getLocationTerms(),
                intent.isGenericCompanionRequired(),
                intent.getRelationPolicy(),
                intent.getTimeIntent(),
                intent.getPriorityIntent(),
                intent.getStatusIntents(),
                intent.getSyncIntent(),
                intent.getOverallConfidence());

        if (shouldFallback(intent)) {
            log.info("Task search fallback. query='{}', topicWeak={}, mainActionWeak={}, genericCompanionRequired={}, relationPolicy={}, suggestedQueries={}",
                    query,
                    !intent.hasUsefulTopicTerms(),
                    !intent.hasUsefulMainAction(),
                    intent.isGenericCompanionRequired(),
                    intent.getRelationPolicy(),
                    intent.getSuggestedQueries());
            return ProjectTaskSearchResponse.of(
                    query,
                    true,
                    SearchIntentResponse.from(intent),
                    List.of(),
                    List.of(),
                    truncateSuggestions(intent.getSuggestedQueries())
            );
        }

        List<Task> tasks = taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(SecurityContextHelper.getCurrentUserId());
        taskSearchEmbeddingService.ensureEmbeddings(tasks);
        Map<Long, Double> semanticSimilarities = taskSearchEmbeddingService.searchSimilarities(intent);

        List<ScoredTask> rankedTasks = taskSyncStateResolver.resolveAll(tasks).stream()
                .map(snapshot -> scoreTask(snapshot, intent, semanticSimilarities.getOrDefault(snapshot.getTask().getId(), 0.0d)))
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingInt(ScoredTask::totalScore).reversed()
                        .thenComparing(scored -> scored.snapshot().getTask().getDueAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(scored -> scored.snapshot().getTask().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(scored -> scored.snapshot().getTask().getId(), Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(TASK_RESULT_LIMIT)
                .collect(Collectors.toList());

        logTopCandidates(query, rankedTasks);

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
                            scored.totalScore()
                    );
                })
                .collect(Collectors.toList());

        List<RelatedProjectSearchResultResponse> relatedProjects = buildRelatedProjects(rankedTasks);

        return ProjectTaskSearchResponse.of(
                query,
                false,
                SearchIntentResponse.from(intent),
                taskResults,
                relatedProjects,
                List.of()
        );
    }

    private boolean shouldFallback(SearchIntent intent) {
        int structuredSignalCount = structuredSignalCount(intent);
        if (structuredSignalCount == 0) {
            return true;
        }

        return intent.getQueryType() == SearchQueryType.BROAD_SEARCH
                && structuredSignalCount == 1
                && intent.getOverallConfidence() < 0.55d;
    }

    private SearchIntent normalizeIntent(SearchIntent parsedIntent) {
        SearchIntent enrichedIntent = enrichIntentFromRawQuery(parsedIntent);
        SearchQueryType queryType = deriveQueryType(enrichedIntent);
        SearchRelationPolicy relationPolicy = queryType == SearchQueryType.RELATIONAL_SEARCH
                ? SearchRelationPolicy.PREFER_ALL
                : SearchRelationPolicy.ALLOW_PARTIAL;

        return SearchIntent.of(
                enrichedIntent.getRawQuery(),
                queryType,
                enrichedIntent.getTargetType(),
                enrichedIntent.getDomainType(),
                enrichedIntent.getMainAction(),
                enrichedIntent.getSecondaryActions(),
                enrichedIntent.getTopicTerms(),
                enrichedIntent.getParticipantTerms(),
                enrichedIntent.getLocationTerms(),
                enrichedIntent.isGenericCompanionRequired(),
                enrichedIntent.getTimeIntent(),
                enrichedIntent.getPriorityIntent(),
                enrichedIntent.getStatusIntents(),
                enrichedIntent.getSyncIntent(),
                relationPolicy,
                enrichedIntent.getOverallConfidence(),
                enrichedIntent.getFieldConfidence(),
                enrichedIntent.getSuggestedQueries()
        );
    }

    private SearchIntent enrichIntentFromRawQuery(SearchIntent parsedIntent) {
        String normalizedQuery = normalizeSingle(parsedIntent.getRawQuery());

        LinkedHashSet<String> participantTerms = new LinkedHashSet<>(sanitizeParticipantTerms(parsedIntent.getParticipantTerms()));
        participantTerms.addAll(extractParticipantHints(normalizedQuery));

        LinkedHashSet<String> locationTerms = new LinkedHashSet<>(parsedIntent.getLocationTerms());
        locationTerms.addAll(extractLocationHints(normalizedQuery));

        boolean genericCompanionRequired = parsedIntent.isGenericCompanionRequired()
                || detectGenericCompanionRequired(normalizedQuery, participantTerms, locationTerms, parsedIntent);

        SearchActionIntent mainAction = parsedIntent.getMainAction();
        LinkedHashSet<SearchActionIntent> secondaryActions = new LinkedHashSet<>(parsedIntent.getSecondaryActions());

        boolean visitPattern = !locationTerms.isEmpty() && containsAny(normalizedQuery, VISIT_QUERY_HINTS);
        boolean meetPattern = (!participantTerms.isEmpty() || genericCompanionRequired) && containsAny(normalizedQuery, MEET_QUERY_HINTS);

        if (visitPattern) {
            if (mainAction != SearchActionIntent.VISIT && mainAction != SearchActionIntent.UNKNOWN) {
                secondaryActions.add(mainAction);
            }
            mainAction = SearchActionIntent.VISIT;
            if (meetPattern) {
                secondaryActions.add(SearchActionIntent.MEET);
            }
        } else if (mainAction == SearchActionIntent.UNKNOWN && meetPattern) {
            mainAction = SearchActionIntent.MEET;
        }

        SearchDomainType domainType = parsedIntent.getDomainType();
        if (domainType == SearchDomainType.UNKNOWN || domainType == SearchDomainType.MIXED) {
            if (containsAny(locationTerms, LIFE_LOCATION_TERMS)) {
                domainType = SearchDomainType.LIFE;
            } else if (!participantTerms.isEmpty()) {
                domainType = SearchDomainType.PERSONAL;
            }
        }

        return SearchIntent.of(
                parsedIntent.getRawQuery(),
                parsedIntent.getQueryType(),
                parsedIntent.getTargetType(),
                domainType,
                mainAction,
                new ArrayList<>(secondaryActions),
                parsedIntent.getTopicTerms(),
                new ArrayList<>(participantTerms),
                new ArrayList<>(locationTerms),
                genericCompanionRequired,
                parsedIntent.getTimeIntent(),
                parsedIntent.getPriorityIntent(),
                parsedIntent.getStatusIntents(),
                parsedIntent.getSyncIntent(),
                parsedIntent.getRelationPolicy(),
                parsedIntent.getOverallConfidence(),
                parsedIntent.getFieldConfidence(),
                parsedIntent.getSuggestedQueries()
        );
    }

    private SearchQueryType deriveQueryType(SearchIntent intent) {
        if (!intent.hasUsefulStructuredSignal()) {
            return SearchQueryType.BROAD_SEARCH;
        }
        if (isRelationalQuery(intent)) {
            return SearchQueryType.RELATIONAL_SEARCH;
        }
        if (intent.hasUsefulTopicTerms() || intent.hasUsefulMainAction()) {
            return SearchQueryType.TOPIC_SEARCH;
        }
        return SearchQueryType.BROAD_SEARCH;
    }

    private boolean isRelationalQuery(SearchIntent intent) {
        boolean hasRelationalRole = intent.hasUsefulParticipantTerms()
                || intent.hasUsefulLocationTerms()
                || intent.isGenericCompanionRequired();
        if (!hasRelationalRole) {
            return false;
        }

        int relationRoleCount = relationRoleCount(intent);
        if (relationRoleCount < 2) {
            return false;
        }

        return intent.isGenericCompanionRequired()
                || containsRelationalConnector(intent.getRawQuery())
                || intent.getQueryType() == SearchQueryType.RELATIONAL_SEARCH;
    }

    private int structuredSignalCount(SearchIntent intent) {
        int structuredSignalCount = 0;
        if (intent.hasUsefulTopicTerms()) {
            structuredSignalCount++;
        }
        if (intent.hasUsefulMainAction()) {
            structuredSignalCount++;
        }
        if (intent.hasUsefulParticipantTerms()) {
            structuredSignalCount++;
        }
        if (intent.hasUsefulLocationTerms()) {
            structuredSignalCount++;
        }
        return structuredSignalCount;
    }

    private int relationRoleCount(SearchIntent intent) {
        int relationRoleCount = 0;
        if (intent.hasUsefulMainAction()) {
            relationRoleCount++;
        }
        if (intent.isGenericCompanionRequired()) {
            relationRoleCount++;
        }
        if (intent.hasUsefulParticipantTerms()) {
            relationRoleCount++;
        }
        if (intent.hasUsefulLocationTerms()) {
            relationRoleCount++;
        }
        return relationRoleCount;
    }

    private boolean containsRelationalConnector(String rawQuery) {
        String normalized = normalizeSingle(rawQuery);
        return normalized.contains("같이")
                || normalized.contains("함께")
                || normalized.contains("동행")
                || normalized.contains("이랑")
                || normalized.contains("랑")
                || normalized.contains("들과")
                || normalized.contains("하고")
                || normalized.contains("와 함께")
                || normalized.contains("과 함께")
                || normalized.contains("만나서")
                || normalized.contains("데리고");
    }

    private List<String> extractParticipantHints(String normalizedQuery) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String candidate : PARTICIPANT_HINT_TERMS) {
            String normalizedCandidate = normalizeSingle(candidate);
            if (!normalizedCandidate.isBlank() && normalizedQuery.contains(normalizedCandidate)) {
                terms.add(candidate);
            }
        }
        return new ArrayList<>(terms);
    }

    private List<String> sanitizeParticipantTerms(List<String> participantTerms) {
        return participantTerms.stream()
                .map(this::normalizeSingle)
                .filter(term -> !term.isBlank() && !GENERIC_COMPANION_TERMS.contains(term))
                .collect(Collectors.toList());
    }

    private boolean detectGenericCompanionRequired(String normalizedQuery,
                                                   Set<String> participantTerms,
                                                   Set<String> locationTerms,
                                                   SearchIntent parsedIntent) {
        if (!participantTerms.isEmpty()) {
            return false;
        }
        if (!containsAny(normalizedQuery, GENERIC_COMPANION_QUERY_CUES)) {
            return false;
        }
        return !locationTerms.isEmpty()
                || parsedIntent.hasUsefulMainAction()
                || parsedIntent.hasUsefulTopicTerms();
    }

    private List<String> extractLocationHints(String normalizedQuery) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String candidate : LOCATION_HINT_TERMS) {
            String normalizedCandidate = normalizeSingle(candidate);
            if (!normalizedCandidate.isBlank() && normalizedQuery.contains(normalizedCandidate)) {
                terms.add(candidate);
            }
        }
        return new ArrayList<>(terms);
    }

    private boolean containsAny(String normalizedText, List<String> candidates) {
        for (String candidate : candidates) {
            String normalizedCandidate = normalizeSingle(candidate);
            if (!normalizedCandidate.isBlank() && normalizedText.contains(normalizedCandidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(Collection<String> values, List<String> candidates) {
        for (String value : values) {
            String normalizedValue = normalizeSingle(value);
            for (String candidate : candidates) {
                String normalizedCandidate = normalizeSingle(candidate);
                if (!normalizedCandidate.isBlank() && normalizedValue.equals(normalizedCandidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> truncateSuggestions(List<String> suggestedQueries) {
        if (suggestedQueries.isEmpty()) {
            return List.of(
                    "이번 주 마감 일정",
                    "캘린더 반영 안 된 일정",
                    "차단된 작업"
            );
        }
        return suggestedQueries.stream().limit(3).collect(Collectors.toList());
    }

    private ScoredTask scoreTask(SummaryTaskSnapshot snapshot, SearchIntent intent, double semanticSimilarity) {
        Task task = snapshot.getTask();
        String text = normalizeText(task.getProject().getName(), task.getTitle(), task.getDescription());

        MatchScore topicScore = topicScore(intent.getTopicTerms(), text);
        MatchScore participantScore = termScore(intent.getParticipantTerms(), text);
        MatchScore locationScore = termScore(intent.getLocationTerms(), text);
        MatchScore companionScore = companionScore(intent, text);
        MatchScore roleEntityScore = roleEntityScore(topicScore, participantScore, locationScore, companionScore);
        MatchScore mainActionScore = actionScore(intent.getMainAction(), text);
        MatchScore secondaryActionScore = secondaryActionScore(intent.getSecondaryActions(), text);
        TimeScore timeScore = timeScore(intent.getTimeIntent(), task);
        if (timeScore.filtered()) {
            return null;
        }

        if (!passesMustMatch(intent, topicScore, participantScore, locationScore, companionScore, mainActionScore)) {
            return null;
        }

        int relationScore = relationScore(intent, topicScore, participantScore, locationScore, companionScore, mainActionScore);
        int domainScore = domainScore(intent.getDomainType(), text);
        int semanticScore = semanticBoost(intent, topicScore, participantScore, locationScore, mainActionScore, semanticSimilarity);
        int statusScore = statusScore(intent.getStatusIntents(), task.getStatus());
        int syncScore = syncScore(intent.getSyncIntent(), snapshot.getSyncState());
        int priorityScore = priorityScore(intent.getPriorityIntent(), task);

        int total = roleEntityScore.score()
                + mainActionScore.score()
                + secondaryActionScore.score()
                + relationScore
                + timeScore.score()
                + domainScore
                + semanticScore
                + statusScore
                + syncScore
                + priorityScore;

        boolean hasStructuredMatch = roleEntityScore.score() > 0
                || mainActionScore.score() > 0
                || secondaryActionScore.score() > 0
                || relationScore > 0;

        if (!hasStructuredMatch && semanticScore < 14) {
            return null;
        }
        if (!hasStructuredMatch && semanticScore == 0) {
            return null;
        }
        if (total <= 0) {
            return null;
        }

        return new ScoredTask(
                snapshot,
                total,
                roleEntityScore.score(),
                mainActionScore.score(),
                secondaryActionScore.score(),
                relationScore,
                timeScore.score(),
                domainScore,
                semanticScore,
                statusScore,
                syncScore,
                priorityScore,
                semanticSimilarity
        );
    }

    private boolean passesMustMatch(SearchIntent intent,
                                    MatchScore topicScore,
                                    MatchScore participantScore,
                                    MatchScore locationScore,
                                    MatchScore companionScore,
                                    MatchScore mainActionScore) {
        if (intent.getQueryType() == SearchQueryType.TOPIC_SEARCH) {
            return passesTopicMust(intent, topicScore);
        }

        if (intent.getQueryType() == SearchQueryType.RELATIONAL_SEARCH) {
            return passesRelationalMust(intent, topicScore, participantScore, locationScore, companionScore, mainActionScore);
        }

        return passesBroadMust(intent, topicScore, participantScore, locationScore, companionScore, mainActionScore);
    }

    private boolean passesTopicMust(SearchIntent intent, MatchScore topicScore) {
        if (!intent.hasUsefulTopicTerms()) {
            return intent.hasUsefulMainAction();
        }
        return topicScore.score() > 0;
    }

    private boolean passesRelationalMust(SearchIntent intent,
                                         MatchScore topicScore,
                                         MatchScore participantScore,
                                         MatchScore locationScore,
                                         MatchScore companionScore,
                                         MatchScore mainActionScore) {
        if (intent.hasUsefulTopicTerms() && topicScore.score() == 0) {
            return false;
        }
        if (intent.hasUsefulParticipantTerms() && participantScore.score() == 0) {
            return false;
        }
        if (intent.isGenericCompanionRequired() && companionScore.score() == 0) {
            return false;
        }
        if (intent.hasUsefulLocationTerms() && locationScore.score() == 0) {
            return false;
        }
        return !intent.hasUsefulMainAction() || mainActionScore.score() > 0;
    }

    private boolean passesBroadMust(SearchIntent intent,
                                    MatchScore topicScore,
                                    MatchScore participantScore,
                                    MatchScore locationScore,
                                    MatchScore companionScore,
                                    MatchScore mainActionScore) {
        if (intent.hasUsefulTopicTerms() && topicScore.score() > 0) {
            return true;
        }
        if (intent.hasUsefulParticipantTerms() && participantScore.score() > 0) {
            return true;
        }
        if (intent.isGenericCompanionRequired() && companionScore.score() > 0) {
            return true;
        }
        if (intent.hasUsefulLocationTerms() && locationScore.score() > 0) {
            return true;
        }
        return intent.hasUsefulMainAction() && mainActionScore.score() > 0;
    }

    private MatchScore roleEntityScore(MatchScore topicScore,
                                       MatchScore participantScore,
                                       MatchScore locationScore,
                                       MatchScore companionScore) {
        int activeGroups = 0;
        int matchedGroups = 0;
        int semanticGroups = 0;

        if (topicScore.active()) {
            activeGroups++;
            if (topicScore.score() > 0) {
                matchedGroups++;
            }
            if (topicScore.semanticMatched()) {
                semanticGroups++;
            }
        }
        if (participantScore.active()) {
            activeGroups++;
            if (participantScore.score() > 0) {
                matchedGroups++;
            }
            if (participantScore.semanticMatched()) {
                semanticGroups++;
            }
        }
        if (locationScore.active()) {
            activeGroups++;
            if (locationScore.score() > 0) {
                matchedGroups++;
            }
            if (locationScore.semanticMatched()) {
                semanticGroups++;
            }
        }
        if (companionScore.active()) {
            activeGroups++;
            if (companionScore.score() > 0) {
                matchedGroups++;
            }
        }

        if (activeGroups == 0 || matchedGroups == 0) {
            return MatchScore.none();
        }

        int perGroup = ROLE_ENTITY_WEIGHT / activeGroups;
        int score = matchedGroups * perGroup;
        if (semanticGroups > 0) {
            score = Math.min(ROLE_ENTITY_WEIGHT, score - Math.min(semanticGroups * 2, 6));
        }
        return new MatchScore(Math.max(score, perGroup), false, semanticGroups > 0, true);
    }

    private int relationScore(SearchIntent intent,
                              MatchScore topicScore,
                              MatchScore participantScore,
                              MatchScore locationScore,
                              MatchScore companionScore,
                              MatchScore mainActionScore) {
        if (intent.getRelationPolicy() != SearchRelationPolicy.PREFER_ALL) {
            return 0;
        }

        int expected = 0;
        int matched = 0;

        if (intent.hasUsefulMainAction()) {
            expected++;
            if (mainActionScore.score() > 0) {
                matched++;
            }
        }
        if (intent.hasUsefulParticipantTerms()) {
            expected++;
            if (participantScore.score() > 0) {
                matched++;
            }
        }
        if (intent.isGenericCompanionRequired()) {
            expected++;
            if (companionScore.score() > 0) {
                matched++;
            }
        }
        if (intent.hasUsefulLocationTerms()) {
            expected++;
            if (locationScore.score() > 0) {
                matched++;
            }
        }
        if (intent.hasUsefulTopicTerms()) {
            expected++;
            if (topicScore.score() > 0) {
                matched++;
            }
        }

        if (expected <= 1) {
            return 0;
        }
        if (matched == expected) {
            return RELATION_WEIGHT;
        }
        if (matched >= expected - 1) {
            return RELATION_WEIGHT / 3;
        }
        return -10;
    }

    private int semanticBoost(SearchIntent intent,
                              MatchScore topicScore,
                              MatchScore participantScore,
                              MatchScore locationScore,
                              MatchScore mainActionScore,
                              double semanticSimilarity) {
        int baseScore = semanticScore(semanticSimilarity);
        if (baseScore == 0) {
            return 0;
        }

        if (intent.getQueryType() == SearchQueryType.TOPIC_SEARCH) {
            return topicScore.score() > 0 ? baseScore : 0;
        }

        if (intent.getQueryType() == SearchQueryType.RELATIONAL_SEARCH) {
            boolean roleMatched = topicScore.score() > 0
                    || participantScore.score() > 0
                    || locationScore.score() > 0
                    || mainActionScore.score() > 0;
            return roleMatched ? baseScore : 0;
        }

        return baseScore;
    }

    private MatchScore topicScore(List<String> topicTerms, String text) {
        List<String> normalizedTerms = expandTopicTerms(topicTerms).stream()
                .map(this::normalizeSingle)
                .filter(term -> !term.isBlank() && !GENERIC_TERMS.contains(term))
                .collect(Collectors.toList());
        if (normalizedTerms.isEmpty()) {
            return MatchScore.inactive();
        }

        boolean exact = false;
        boolean semantic = false;
        for (String term : normalizedTerms) {
            if (text.contains(term)) {
                exact = true;
                continue;
            }

            List<String> tokens = Arrays.stream(term.split("\\s+"))
                    .map(this::normalizeSingle)
                    .filter(token -> !token.isBlank() && !GENERIC_TERMS.contains(token))
                    .collect(Collectors.toList());
            if (tokens.isEmpty()) {
                continue;
            }

            if (tokens.size() == 1) {
                String token = tokens.get(0);
                String root = token.length() > 2 ? token.substring(0, token.length() - 1) : token;
                if (root.length() >= 2 && text.contains(root)) {
                    semantic = true;
                }
                continue;
            }

            long matchedTokens = tokens.stream()
                    .filter(text::contains)
                    .count();
            if (matchedTokens == tokens.size()) {
                semantic = true;
            }
        }

        if (exact) {
            return MatchScore.exact();
        }
        if (semantic) {
            return MatchScore.semantic();
        }
        return MatchScore.none();
    }

    private List<String> expandTopicTerms(List<String> topicTerms) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String term : topicTerms) {
            String normalizedTerm = normalizeSingle(term);
            if (normalizedTerm.isBlank()) {
                continue;
            }
            expanded.add(normalizedTerm);
            if (LEISURE_TOPIC_TERMS.contains(normalizedTerm)) {
                expanded.addAll(LEISURE_TOPIC_EXPANSIONS);
            }
            expanded.addAll(TOPIC_PHRASE_EXPANSIONS.getOrDefault(normalizedTerm, List.of()));
        }
        return new ArrayList<>(expanded);
    }

    private MatchScore termScore(List<String> terms, String text) {
        List<String> normalizedTerms = terms.stream()
                .map(this::normalizeSingle)
                .filter(term -> !term.isBlank() && !GENERIC_TERMS.contains(term))
                .collect(Collectors.toList());
        if (normalizedTerms.isEmpty()) {
            return MatchScore.inactive();
        }

        boolean exact = false;
        boolean semantic = false;
        for (String term : normalizedTerms) {
            for (String candidate : expandSearchTerm(term)) {
                if (text.contains(candidate)) {
                    exact = true;
                    break;
                }
                for (String token : candidate.split("\\s+")) {
                    if (token.length() >= 2 && text.contains(token)) {
                        semantic = true;
                        break;
                    }
                }
                if (exact || semantic) {
                    break;
                }
            }
        }

        if (exact) {
            return MatchScore.exact();
        }
        if (semantic) {
            return MatchScore.semantic();
        }
        return MatchScore.none();
    }

    private MatchScore companionScore(SearchIntent intent, String text) {
        if (!intent.isGenericCompanionRequired()) {
            return MatchScore.inactive();
        }

        if (containsAny(text, TASK_COMPANION_CUES)) {
            return MatchScore.exact();
        }

        for (String candidate : PARTICIPANT_HINT_TERMS) {
            String normalizedCandidate = normalizeSingle(candidate);
            if (!normalizedCandidate.isBlank() && text.contains(normalizedCandidate)) {
                return MatchScore.exact();
            }
        }

        return MatchScore.none();
    }

    private List<String> expandSearchTerm(String term) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        expanded.add(term);

        if (term.endsWith("들") && term.length() > 1) {
            expanded.add(term.substring(0, term.length() - 1));
        }

        if (term.endsWith("들과") && term.length() > 2) {
            expanded.add(term.substring(0, term.length() - 2));
        }

        if (term.endsWith("이들") && term.length() > 2) {
            expanded.add(term.substring(0, term.length() - 2));
        }

        return new ArrayList<>(expanded);
    }

    private MatchScore actionScore(SearchActionIntent actionIntent, String text) {
        if (actionIntent == null || actionIntent == SearchActionIntent.UNKNOWN) {
            return MatchScore.inactive();
        }
        List<String> keywords = ACTION_KEYWORDS.getOrDefault(actionIntent, List.of());
        if (keywords.isEmpty()) {
            return MatchScore.none();
        }

        boolean exact = false;
        boolean semantic = false;
        for (String keyword : keywords) {
            String normalizedKeyword = normalizeSingle(keyword);
            if (normalizedKeyword.isBlank()) {
                continue;
            }
            if (text.contains(normalizedKeyword)) {
                exact = true;
                continue;
            }
            String root = normalizedKeyword.length() > 2 ? normalizedKeyword.substring(0, normalizedKeyword.length() - 1) : normalizedKeyword;
            if (root.length() >= 2 && text.contains(root)) {
                semantic = true;
            }
        }

        if (exact) {
            return new MatchScore(MAIN_ACTION_WEIGHT, true, false, true);
        }
        if (semantic) {
            return new MatchScore(Math.max(MAIN_ACTION_WEIGHT - 8, 8), false, true, true);
        }
        return MatchScore.none();
    }

    private MatchScore secondaryActionScore(List<SearchActionIntent> actionIntents, String text) {
        List<SearchActionIntent> effectiveIntents = actionIntents.stream()
                .filter(intent -> intent != SearchActionIntent.UNKNOWN)
                .collect(Collectors.toList());
        if (effectiveIntents.isEmpty()) {
            return MatchScore.inactive();
        }

        int matched = 0;
        int semanticMatched = 0;
        for (SearchActionIntent intent : effectiveIntents) {
            MatchScore score = actionScore(intent, text);
            if (score.score() > 0) {
                matched++;
                if (score.semanticMatched()) {
                    semanticMatched++;
                }
            }
        }

        if (matched == 0) {
            return MatchScore.none();
        }

        int perAction = Math.max(SECONDARY_ACTION_WEIGHT / effectiveIntents.size(), 4);
        int score = Math.min(SECONDARY_ACTION_WEIGHT, matched * perAction);
        if (semanticMatched > 0) {
            score = Math.max(score - semanticMatched, 4);
        }
        return new MatchScore(score, semanticMatched == 0, semanticMatched > 0, true);
    }

    private TimeScore timeScore(SearchTimeIntent timeIntent, Task task) {
        LocalDateTime dueAt = task.getDueAt();
        LocalDateTime now = LocalDateTime.now();
        switch (timeIntent) {
            case TODAY:
                return timeWindowScore(dueAt, now.toLocalDate(), now.toLocalDate(), true);
            case THIS_WEEK:
                LocalDate start = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate end = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                return timeWindowScore(dueAt, start, end, true);
            case THIS_MONTH:
                YearMonth yearMonth = YearMonth.from(now);
                return timeWindowScore(dueAt, yearMonth.atDay(1), yearMonth.atEndOfMonth(), true);
            case UPCOMING:
                if (dueAt == null) {
                    return TimeScore.none();
                }
                if (dueAt.isAfter(now.minusHours(1))) {
                    return new TimeScore(TIME_WEIGHT, false);
                }
                return new TimeScore(0, true);
            case RECENT:
                if (task.getUpdatedAt() != null && task.getUpdatedAt().isAfter(now.minusDays(14))) {
                    return new TimeScore(TIME_WEIGHT, false);
                }
                return TimeScore.none();
            case OVERDUE:
                if (dueAt != null && dueAt.isBefore(now)) {
                    return new TimeScore(TIME_WEIGHT, false);
                }
                return new TimeScore(0, true);
            case DEFERRED:
                if (dueAt != null && dueAt.isBefore(now)) {
                    return new TimeScore(TIME_WEIGHT, false);
                }
                if (task.getUpdatedAt() != null && task.getUpdatedAt().isBefore(now.minusDays(10))) {
                    return new TimeScore(TIME_WEIGHT / 2, false);
                }
                return TimeScore.none();
            case UNSPECIFIED:
            default:
                return TimeScore.none();
        }
    }

    private TimeScore timeWindowScore(LocalDateTime dueAt, LocalDate start, LocalDate end, boolean hardFilter) {
        if (dueAt == null) {
            return hardFilter ? new TimeScore(0, true) : TimeScore.none();
        }
        LocalDate dueDate = dueAt.toLocalDate();
        if ((dueDate.isEqual(start) || dueDate.isAfter(start)) && (dueDate.isEqual(end) || dueDate.isBefore(end))) {
            return new TimeScore(TIME_WEIGHT, false);
        }
        return new TimeScore(0, hardFilter);
    }

    private int domainScore(SearchDomainType domainType, String text) {
        if (domainType == null || domainType == SearchDomainType.UNKNOWN || domainType == SearchDomainType.MIXED) {
            return 0;
        }
        List<String> keywords = DOMAIN_KEYWORDS.getOrDefault(domainType, List.of());
        for (String keyword : keywords) {
            String normalizedKeyword = normalizeSingle(keyword);
            if (!normalizedKeyword.isBlank() && text.contains(normalizedKeyword)) {
                return DOMAIN_WEIGHT;
            }
        }
        if (domainType == SearchDomainType.WORK && matchesAnyDomain(SearchDomainType.PERSONAL, text, SearchDomainType.LIFE)) {
            return -DOMAIN_WEIGHT;
        }
        if ((domainType == SearchDomainType.PERSONAL || domainType == SearchDomainType.LIFE) && matchesAnyDomain(SearchDomainType.WORK, text)) {
            return -DOMAIN_WEIGHT;
        }
        return 0;
    }

    private boolean matchesAnyDomain(SearchDomainType primary, String text, SearchDomainType... others) {
        if (matchesDomain(primary, text)) {
            return true;
        }
        for (SearchDomainType other : others) {
            if (matchesDomain(other, text)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDomain(SearchDomainType domainType, String text) {
        for (String keyword : DOMAIN_KEYWORDS.getOrDefault(domainType, List.of())) {
            String normalizedKeyword = normalizeSingle(keyword);
            if (!normalizedKeyword.isBlank() && text.contains(normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }

    private int semanticScore(double similarity) {
        if (similarity >= 0.90d) {
            return SEMANTIC_WEIGHT;
        }
        if (similarity >= 0.82d) {
            return 14;
        }
        if (similarity >= 0.74d) {
            return 8;
        }
        return 0;
    }

    private int statusScore(List<TaskStatus> statusIntents, TaskStatus status) {
        if (statusIntents.isEmpty() || status == null) {
            return 0;
        }
        return statusIntents.contains(status) ? STATUS_MATCH_BOOST : STATUS_MISMATCH_PENALTY;
    }

    private int syncScore(SearchSyncIntent syncIntent, TaskSyncState syncState) {
        if (syncIntent == null || syncIntent == SearchSyncIntent.ANY || syncState == null) {
            return 0;
        }
        boolean matches;
        switch (syncIntent) {
            case SYNCED:
                matches = syncState == TaskSyncState.SYNCED;
                break;
            case UNSYNCED:
                matches = syncState == TaskSyncState.PENDING_SYNC
                        || syncState == TaskSyncState.SYNC_DISABLED
                        || syncState == TaskSyncState.DELETE_PENDING;
                break;
            case FAILED:
                matches = syncState == TaskSyncState.FAILED_SYNC
                        || syncState == TaskSyncState.DELETE_FAILED;
                break;
            case ANY:
            default:
                matches = true;
                break;
        }
        return matches ? SYNC_MATCH_BOOST : SYNC_MISMATCH_PENALTY;
    }

    private int priorityScore(SearchPriorityIntent priorityIntent, Task task) {
        if (priorityIntent == null || priorityIntent == SearchPriorityIntent.NONE) {
            return 0;
        }
        LocalDateTime dueAt = task.getDueAt();
        switch (priorityIntent) {
            case URGENT:
                return dueAt != null && dueAt.isBefore(LocalDateTime.now().plusDays(2)) ? PRIORITY_MATCH_BOOST : 0;
            case IMPORTANT:
                return task.getStatus() == TaskStatus.BLOCKED || task.getStatus() == TaskStatus.IN_PROGRESS ? PRIORITY_MATCH_BOOST : 0;
            case MUST_DO:
                return task.getStatus() != TaskStatus.DONE ? PRIORITY_MATCH_BOOST : 0;
            case DEFERRED:
                return dueAt != null && dueAt.isBefore(LocalDateTime.now()) ? PRIORITY_MATCH_BOOST : 0;
            case NONE:
            default:
                return 0;
        }
    }

    private List<RelatedProjectSearchResultResponse> buildRelatedProjects(List<ScoredTask> rankedTasks) {
        Map<Long, List<ScoredTask>> grouped = rankedTasks.stream()
                .collect(Collectors.groupingBy(scored -> scored.snapshot().getTask().getProject().getId()));

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
                            score
                    );
                })
                .sorted(Comparator.comparingInt(RelatedProjectSearchResultResponse::getScore).reversed())
                .limit(PROJECT_RESULT_LIMIT)
                .collect(Collectors.toList());
    }

    private void logTopCandidates(String query, List<ScoredTask> rankedTasks) {
        if (rankedTasks.isEmpty()) {
            log.info("Task search produced no ranked candidates. query='{}'", query);
            return;
        }
        List<String> topCandidates = rankedTasks.stream()
                .limit(10)
                .map(ScoredTask::debugSummary)
                .collect(Collectors.toList());
        log.info("Task search top candidates. query='{}', candidates={}", query, topCandidates);
    }

    private String normalizeText(String... values) {
        return Arrays.stream(values)
                .filter(Objects::nonNull)
                .map(this::normalizeSingle)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(" "));
    }

    private String normalizeSingle(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^0-9a-zA-Z가-힣\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static final class MatchScore {
        private final int score;
        private final boolean exact;
        private final boolean semantic;
        private final boolean active;

        private MatchScore(int score, boolean exact, boolean semantic, boolean active) {
            this.score = score;
            this.exact = exact;
            this.semantic = semantic;
            this.active = active;
        }

        private static MatchScore exact() {
            return new MatchScore(ROLE_ENTITY_WEIGHT, true, false, true);
        }

        private static MatchScore semantic() {
            return new MatchScore(Math.max(ROLE_ENTITY_WEIGHT - 9, 8), false, true, true);
        }

        private static MatchScore none() {
            return new MatchScore(0, false, false, true);
        }

        private static MatchScore inactive() {
            return new MatchScore(0, false, false, false);
        }

        private int score() {
            return score;
        }

        private boolean semanticMatched() {
            return semantic;
        }

        private boolean active() {
            return active;
        }
    }

    private static final class TimeScore {
        private final int score;
        private final boolean filtered;

        private TimeScore(int score, boolean filtered) {
            this.score = score;
            this.filtered = filtered;
        }

        private static TimeScore none() {
            return new TimeScore(0, false);
        }

        private int score() {
            return score;
        }

        private boolean filtered() {
            return filtered;
        }
    }

    private static final class ScoredTask {
        private final SummaryTaskSnapshot snapshot;
        private final int totalScore;
        private final int roleEntityScore;
        private final int mainActionScore;
        private final int secondaryActionScore;
        private final int relationScore;
        private final int timeScore;
        private final int domainScore;
        private final int semanticScore;
        private final int statusScore;
        private final int syncScore;
        private final int priorityScore;
        private final double semanticSimilarity;

        private ScoredTask(SummaryTaskSnapshot snapshot,
                           int totalScore,
                           int roleEntityScore,
                           int mainActionScore,
                           int secondaryActionScore,
                           int relationScore,
                           int timeScore,
                           int domainScore,
                           int semanticScore,
                           int statusScore,
                           int syncScore,
                           int priorityScore,
                           double semanticSimilarity) {
            this.snapshot = snapshot;
            this.totalScore = totalScore;
            this.roleEntityScore = roleEntityScore;
            this.mainActionScore = mainActionScore;
            this.secondaryActionScore = secondaryActionScore;
            this.relationScore = relationScore;
            this.timeScore = timeScore;
            this.domainScore = domainScore;
            this.semanticScore = semanticScore;
            this.statusScore = statusScore;
            this.syncScore = syncScore;
            this.priorityScore = priorityScore;
            this.semanticSimilarity = semanticSimilarity;
        }

        private SummaryTaskSnapshot snapshot() {
            return snapshot;
        }

        private int totalScore() {
            return totalScore;
        }

        private String debugSummary() {
            Task task = snapshot.getTask();
            return "taskId=" + task.getId()
                    + ", title='" + task.getTitle() + '\''
                    + ", total=" + totalScore
                    + ", roleEntity=" + roleEntityScore
                    + ", mainAction=" + mainActionScore
                    + ", secondaryAction=" + secondaryActionScore
                    + ", relation=" + relationScore
                    + ", time=" + timeScore
                    + ", domain=" + domainScore
                    + ", semantic=" + semanticScore + String.format(Locale.ROOT, "(%.3f)", semanticSimilarity)
                    + ", status=" + statusScore
                    + ", sync=" + syncScore
                    + ", priority=" + priorityScore;
        }
    }
}
