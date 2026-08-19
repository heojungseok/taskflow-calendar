package com.taskflow.calendar.domain.search;

import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.search.dto.ProjectTaskSearchResponse;
import com.taskflow.calendar.domain.search.generator.TaskSearchIntentParser;
import com.taskflow.calendar.domain.summary.SummaryTaskSnapshot;
import com.taskflow.calendar.domain.summary.TaskSyncState;
import com.taskflow.calendar.domain.summary.TaskSyncStateResolver;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectTaskSearchServiceTest {

    /**
     * 소유권 격리가 들어간 뒤로 서비스가 현재 사용자를 요구한다.
     * SecurityContextHolder는 스레드 로컬이라 테스트 간에 새어나가므로 명시적으로 설정하고 정리한다.
     */
    @org.junit.jupiter.api.BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, null));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskSyncStateResolver taskSyncStateResolver;

    @Mock
    private TaskSearchIntentParser taskSearchIntentParser;

    @Mock
    private TaskSearchEmbeddingService taskSearchEmbeddingService;

    private ProjectTaskSearchService service;

    @BeforeEach
    void setUp() {
        service = new ProjectTaskSearchService(taskRepository, taskSyncStateResolver, taskSearchIntentParser, taskSearchEmbeddingService);
        stubResolveAllViaResolve();
    }

    /**
     * 서비스는 목록을 resolveAll로 한 번에 해석한다(N+1 제거).
     * 이 테스트들이 검증하는 것은 배치 자체가 아니라 점수·정렬이므로,
     * 기존의 Task별 resolve 스텁을 그대로 살려 resolveAll이 그것을 위임하게 둔다.
     */
    private void stubResolveAllViaResolve() {
        lenient().when(taskSyncStateResolver.resolveAll(anyList())).thenAnswer(invocation -> {
            List<Task> tasks = invocation.getArgument(0);
            return tasks.stream().map(taskSyncStateResolver::resolve).toList();
        });
    }

    @Test
    @DisplayName("search_핵심필드가비어있으면_fallback을반환한다")
    void search_returnsFallbackWhenCoreFieldsAreWeak() {
        SearchIntent intent = SearchIntent.of(
                "중요한 거 찾아줘",
                SearchQueryType.BROAD_SEARCH,
                SearchTargetType.MIXED,
                SearchDomainType.UNKNOWN,
                SearchActionIntent.UNKNOWN,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                SearchTimeIntent.UNSPECIFIED,
                SearchPriorityIntent.IMPORTANT,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.ALLOW_PARTIAL,
                0.91d,
                Map.of("topicTerms", 0.1d, "mainAction", 0.2d),
                List.of("이번 주 마감 일정", "차단된 작업", "캘린더 반영 안 된 일정")
        );
        when(taskSearchIntentParser.parse("중요한 거 찾아줘")).thenReturn(intent);

        ProjectTaskSearchResponse response = service.search("중요한 거 찾아줘");

        assertTrue(response.isIntentFallback());
        assertEquals(3, response.getSuggestedQueries().size());
        verifyNoInteractions(taskRepository, taskSyncStateResolver);
    }

    @Test
    @DisplayName("search_주제앵커가명확하면_confidence가낮아도검색을진행한다")
    void search_returnsResultsWhenTopicAnchorExistsEvenIfConfidenceIsLow() {
        Project work = project(3L, "Work");
        Task deployTask = task(13L, work, "배포 일정 정리", "운영 배포 관련 항목을 정리합니다.", LocalDateTime.now().plusDays(1));

        SearchIntent intent = SearchIntent.of(
                "배포 관련",
                SearchQueryType.BROAD_SEARCH,
                SearchTargetType.MIXED,
                SearchDomainType.WORK,
                SearchActionIntent.UNKNOWN,
                List.of(),
                List.of("배포"),
                List.of(),
                List.of(),
                SearchTimeIntent.UNSPECIFIED,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.ALLOW_PARTIAL,
                0.42d,
                Map.of("topicTerms", 0.9d, "mainAction", 0.2d),
                List.of("이번 주 배포 준비 일정", "차단된 배포 작업", "캘린더 반영 안 된 배포 일정")
        );
        when(taskSearchIntentParser.parse("배포 관련")).thenReturn(intent);
        when(taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(1L)).thenReturn(List.of(deployTask));
        doNothing().when(taskSearchEmbeddingService).ensureEmbeddings(List.of(deployTask));
        when(taskSearchEmbeddingService.searchSimilarities(any())).thenReturn(new SemanticSearchResult(Map.of(), SemanticSearchStatus.READY));
        when(taskSyncStateResolver.resolve(deployTask)).thenReturn(snapshot(deployTask, TaskSyncState.SYNCED));

        ProjectTaskSearchResponse response = service.search("배포 관련");

        assertTrue(!response.isIntentFallback());
        assertEquals(1, response.getTaskResults().size());
        assertEquals("배포 일정 정리", response.getTaskResults().get(0).getTitle());
        assertEquals("TOPIC_SEARCH", response.getIntent().getQueryType());
    }

    @Test
    @DisplayName("search_유효한의도면_Task우선결과와_관련프로젝트를반환한다")
    void search_returnsRankedTasksAndRelatedProjects() {
        Project alpha = project(1L, "Alpha");
        Project beta = project(2L, "Beta");

        Task alphaTask = task(10L, alpha, "배포 체크리스트 준비", "운영 배포 준비 문서 정리", LocalDateTime.now().plusDays(1));
        Task betaTask = task(11L, beta, "배포 초안 준비", "릴리즈 초안 작성", LocalDateTime.now().plusDays(2));
        Task lifeTask = task(12L, beta, "병원 방문", "검진 일정", LocalDateTime.now().plusDays(1));

        SearchIntent intent = SearchIntent.of(
                "이번 주 배포 준비 일정",
                SearchQueryType.TOPIC_SEARCH,
                SearchTargetType.MIXED,
                SearchDomainType.WORK,
                SearchActionIntent.PREPARE,
                List.of(),
                List.of("배포"),
                List.of(),
                List.of(),
                SearchTimeIntent.THIS_WEEK,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.ALLOW_PARTIAL,
                0.87d,
                Map.of("topicTerms", 0.95d, "mainAction", 0.9d),
                List.of()
        );

        when(taskSearchIntentParser.parse("이번 주 배포 준비 일정")).thenReturn(intent);
        when(taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(1L)).thenReturn(List.of(alphaTask, betaTask, lifeTask));
        doNothing().when(taskSearchEmbeddingService).ensureEmbeddings(List.of(alphaTask, betaTask, lifeTask));
        when(taskSearchEmbeddingService.searchSimilarities(any())).thenReturn(new SemanticSearchResult(Map.of(), SemanticSearchStatus.READY));
        when(taskSyncStateResolver.resolve(alphaTask)).thenReturn(snapshot(alphaTask, TaskSyncState.SYNCED));
        when(taskSyncStateResolver.resolve(betaTask)).thenReturn(snapshot(betaTask, TaskSyncState.PENDING_SYNC));
        when(taskSyncStateResolver.resolve(lifeTask)).thenReturn(snapshot(lifeTask, TaskSyncState.SYNC_DISABLED));

        ProjectTaskSearchResponse response = service.search("이번 주 배포 준비 일정");

        assertTrue(!response.isIntentFallback());
        assertEquals(2, response.getTaskResults().size());
        assertEquals("배포 체크리스트 준비", response.getTaskResults().get(0).getTitle());
        assertEquals("배포 초안 준비", response.getTaskResults().get(1).getTitle());
        assertEquals(2, response.getRelatedProjects().size());
        assertEquals("Alpha", response.getRelatedProjects().get(0).getProjectName());
    }

    @Test
    @DisplayName("search_놀기주제는_나들이같은여가표현으로확장하고_업무결과는제외한다")
    void search_expandsLeisureTopicWithoutPullingWorkNoise() {
        Project personal = project(10L, "Personal");
        Project work = project(11L, "Work");

        Task outingTask = task(20L, personal, "한강 나들이", "주말 외출 일정", LocalDateTime.now().plusDays(2));
        Task workTask = task(21L, work, "배포 체크리스트 정리", "운영 배포 준비 문서 정리", LocalDateTime.now().plusDays(1));

        SearchIntent intent = SearchIntent.of(
                "노는 일정이 뭐가 있었지?",
                SearchQueryType.TOPIC_SEARCH,
                SearchTargetType.MIXED,
                SearchDomainType.PERSONAL,
                SearchActionIntent.MEET,
                List.of(),
                List.of("놀기"),
                List.of(),
                List.of(),
                SearchTimeIntent.UNSPECIFIED,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.ALLOW_PARTIAL,
                0.82d,
                Map.of("topicTerms", 0.91d, "mainAction", 0.88d),
                List.of()
        );

        when(taskSearchIntentParser.parse("노는 일정이 뭐가 있었지?")).thenReturn(intent);
        when(taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(1L)).thenReturn(List.of(outingTask, workTask));
        doNothing().when(taskSearchEmbeddingService).ensureEmbeddings(List.of(outingTask, workTask));
        when(taskSearchEmbeddingService.searchSimilarities(any())).thenReturn(new SemanticSearchResult(Map.of(), SemanticSearchStatus.READY));
        when(taskSyncStateResolver.resolve(outingTask)).thenReturn(snapshot(outingTask, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(workTask)).thenReturn(snapshot(workTask, TaskSyncState.SYNCED));

        ProjectTaskSearchResponse response = service.search("노는 일정이 뭐가 있었지?");

        assertTrue(!response.isIntentFallback());
        assertEquals(1, response.getTaskResults().size());
        assertEquals("한강 나들이", response.getTaskResults().get(0).getTitle());
    }

    @Test
    @DisplayName("search_친구들과노는일정은_참여자조건이없는개인업무일정을제외한다")
    void search_filtersOutTasksWithoutRequiredParticipantInPreferAllQueries() {
        Project personalA = project(20L, "Personal A");
        Project personalB = project(21L, "Personal B");
        Project work = project(22L, "Work B");

        Task outing = task(30L, personalA, "한강 나들이", "친구들과 한강 나들이", LocalDateTime.now().plusDays(5));
        Task friendMeet = task(31L, personalB, "친구 만남 약속 조율", "개인 약속 시간을 맞추고 장소를 정리합니다.", LocalDateTime.now().plusDays(2));
        Task dentist = task(32L, personalB, "치과 방문 준비", "개인 병원 방문 일정입니다. 진료 전 필요한 문진표를 확인합니다. 아무개랑 같이가서.", LocalDateTime.now().plusDays(1));
        Task family = task(33L, personalA, "가족 약속 일정 확인", "주말 가족 약속 시간을 확인하고 이동 일정을 조정합니다.", LocalDateTime.now().plusDays(3));
        Task qaMeeting = task(34L, work, "QA 배포 승인 회의", "배포 전 QA 승인과 운영 반영 일정을 조율하는 회의입니다.", LocalDateTime.now().plusDays(2));

        SearchIntent intent = SearchIntent.of(
                "친구들과 노는 일정이 뭐가 있었지?",
                SearchQueryType.RELATIONAL_SEARCH,
                SearchTargetType.TASK,
                SearchDomainType.PERSONAL,
                SearchActionIntent.MEET,
                List.of(),
                List.of("놀기"),
                List.of("친구"),
                List.of(),
                SearchTimeIntent.UPCOMING,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.PREFER_ALL,
                0.95d,
                Map.of("topicTerms", 0.9d, "participantTerms", 1.0d, "mainAction", 0.9d),
                List.of()
        );

        when(taskSearchIntentParser.parse("친구들과 노는 일정이 뭐가 있었지?")).thenReturn(intent);
        when(taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(1L)).thenReturn(List.of(outing, friendMeet, dentist, family, qaMeeting));
        doNothing().when(taskSearchEmbeddingService).ensureEmbeddings(List.of(outing, friendMeet, dentist, family, qaMeeting));
        when(taskSearchEmbeddingService.searchSimilarities(any())).thenReturn(new SemanticSearchResult(Map.of(
                30L, 0.793d,
                31L, 0.842d,
                32L, 0.823d,
                33L, 0.843d,
                34L, 0.780d
        ), SemanticSearchStatus.READY));
        when(taskSyncStateResolver.resolve(outing)).thenReturn(snapshot(outing, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(friendMeet)).thenReturn(snapshot(friendMeet, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(dentist)).thenReturn(snapshot(dentist, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(family)).thenReturn(snapshot(family, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(qaMeeting)).thenReturn(snapshot(qaMeeting, TaskSyncState.SYNC_DISABLED));

        ProjectTaskSearchResponse response = service.search("친구들과 노는 일정이 뭐가 있었지?");

        assertTrue(!response.isIntentFallback());
        assertEquals(2, response.getTaskResults().size());
        List<String> titles = response.getTaskResults().stream()
                .map(result -> result.getTitle())
                .collect(java.util.stream.Collectors.toList());
        assertTrue(titles.contains("한강 나들이"));
        assertTrue(titles.contains("친구 만남 약속 조율"));
    }

    @Test
    @DisplayName("search_단순주제검색이_RELATIONAL로잘못파싱돼도_server에서_TOPIC으로강등한다")
    void search_downgradesNonRelationalTopicQuery() {
        Project work = project(30L, "Work");
        Task remoteMeeting = task(40L, work, "화상 회의 준비", "온라인 회의 링크와 발표 자료를 확인합니다.", LocalDateTime.now().plusDays(1));

        SearchIntent intent = SearchIntent.of(
                "화상 회의 일정들",
                SearchQueryType.RELATIONAL_SEARCH,
                SearchTargetType.TASK,
                SearchDomainType.WORK,
                SearchActionIntent.MEET,
                List.of(),
                List.of("화상 회의"),
                List.of(),
                List.of(),
                SearchTimeIntent.UPCOMING,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.PREFER_ALL,
                0.9d,
                Map.of("topicTerms", 1.0d, "mainAction", 0.9d),
                List.of()
        );

        when(taskSearchIntentParser.parse("화상 회의 일정들")).thenReturn(intent);
        when(taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(1L)).thenReturn(List.of(remoteMeeting));
        doNothing().when(taskSearchEmbeddingService).ensureEmbeddings(List.of(remoteMeeting));
        when(taskSearchEmbeddingService.searchSimilarities(any())).thenReturn(new SemanticSearchResult(Map.of(), SemanticSearchStatus.READY));
        when(taskSyncStateResolver.resolve(remoteMeeting)).thenReturn(snapshot(remoteMeeting, TaskSyncState.SYNC_DISABLED));

        ProjectTaskSearchResponse response = service.search("화상 회의 일정들");

        assertTrue(!response.isIntentFallback());
        assertEquals("TOPIC_SEARCH", response.getIntent().getQueryType());
        assertEquals("ALLOW_PARTIAL", response.getIntent().getRelationPolicy());
        assertEquals(1, response.getTaskResults().size());
        assertEquals("화상 회의 준비", response.getTaskResults().get(0).getTitle());
    }

    @Test
    @DisplayName("search_화상회의_주제앵커가있으면_MEET만맞는후보를제외한다")
    void search_usesTopicAnchorForVideoMeetingQueries() {
        Project work = project(50L, "Work");
        Project personal = project(51L, "Personal");

        Task onlineMeeting = task(60L, work, "온라인 회의 준비", "줌 회의 링크와 발표 자료를 확인합니다.", LocalDateTime.now().plusDays(1));
        Task qaMeeting = task(61L, work, "QA 배포 승인 회의", "배포 전 QA 승인과 운영 반영 일정을 조율하는 회의입니다.", LocalDateTime.now().plusDays(1));
        Task friendMeet = task(62L, personal, "친구 만남 약속 조율", "개인 약속 시간을 맞추고 장소를 정리합니다.", LocalDateTime.now().plusDays(1));

        SearchIntent intent = SearchIntent.of(
                "화상 회의 일정들",
                SearchQueryType.TOPIC_SEARCH,
                SearchTargetType.TASK,
                SearchDomainType.WORK,
                SearchActionIntent.MEET,
                List.of(),
                List.of("화상 회의"),
                List.of(),
                List.of(),
                SearchTimeIntent.UPCOMING,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.ALLOW_PARTIAL,
                0.95d,
                Map.of("topicTerms", 1.0d, "mainAction", 0.9d),
                List.of()
        );

        when(taskSearchIntentParser.parse("화상 회의 일정들")).thenReturn(intent);
        when(taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(1L)).thenReturn(List.of(onlineMeeting, qaMeeting, friendMeet));
        doNothing().when(taskSearchEmbeddingService).ensureEmbeddings(List.of(onlineMeeting, qaMeeting, friendMeet));
        when(taskSearchEmbeddingService.searchSimilarities(any())).thenReturn(new SemanticSearchResult(Map.of(
                60L, 0.83d,
                61L, 0.83d,
                62L, 0.82d
        ), SemanticSearchStatus.READY));
        when(taskSyncStateResolver.resolve(onlineMeeting)).thenReturn(snapshot(onlineMeeting, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(qaMeeting)).thenReturn(snapshot(qaMeeting, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(friendMeet)).thenReturn(snapshot(friendMeet, TaskSyncState.SYNC_DISABLED));

        ProjectTaskSearchResponse response = service.search("화상 회의 일정들");

        assertTrue(!response.isIntentFallback());
        assertEquals(1, response.getTaskResults().size());
        assertEquals("온라인 회의 준비", response.getTaskResults().get(0).getTitle());
    }

    @Test
    @DisplayName("search_친구들과노는일정은_parser가TOPIC으로줘도_server에서_RELATIONAL로승격한다")
    void search_upgradesRelationalQueryFromLanguageShape() {
        Project personal = project(40L, "Personal");
        Task outing = task(50L, personal, "한강 나들이", "친구들과 한강 나들이", LocalDateTime.now().plusDays(2));

        SearchIntent intent = SearchIntent.of(
                "친구들과 노는 일정이 뭐가 있었지?",
                SearchQueryType.TOPIC_SEARCH,
                SearchTargetType.TASK,
                SearchDomainType.PERSONAL,
                SearchActionIntent.MEET,
                List.of(),
                List.of("놀기"),
                List.of("친구"),
                List.of(),
                SearchTimeIntent.UPCOMING,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.ALLOW_PARTIAL,
                0.88d,
                Map.of("topicTerms", 0.9d, "participantTerms", 0.95d, "mainAction", 0.9d),
                List.of()
        );

        when(taskSearchIntentParser.parse("친구들과 노는 일정이 뭐가 있었지?")).thenReturn(intent);
        when(taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(1L)).thenReturn(List.of(outing));
        doNothing().when(taskSearchEmbeddingService).ensureEmbeddings(List.of(outing));
        when(taskSearchEmbeddingService.searchSimilarities(any())).thenReturn(new SemanticSearchResult(Map.of(), SemanticSearchStatus.READY));
        when(taskSyncStateResolver.resolve(outing)).thenReturn(snapshot(outing, TaskSyncState.SYNC_DISABLED));

        ProjectTaskSearchResponse response = service.search("친구들과 노는 일정이 뭐가 있었지?");

        assertTrue(!response.isIntentFallback());
        assertEquals("RELATIONAL_SEARCH", response.getIntent().getQueryType());
        assertEquals("PREFER_ALL", response.getIntent().getRelationPolicy());
        assertEquals(1, response.getTaskResults().size());
        assertEquals("한강 나들이", response.getTaskResults().get(0).getTitle());
    }

    @Test
    @DisplayName("search_rawQuery역할힌트로_친구와병원조건을보강하고_결합된일정만남긴다")
    void search_enrichesRelationalRolesFromRawQuery() {
        Project personal = project(60L, "Personal");

        Task combined = task(70L, personal, "친구와 병원 동행", "친구와 함께 병원 진료를 보러 갑니다.", LocalDateTime.now().plusDays(1));
        Task friendOnly = task(71L, personal, "친구 만남 약속 조율", "개인 약속 시간을 맞추고 장소를 정리합니다.", LocalDateTime.now().plusDays(2));
        Task hospitalOnly = task(72L, personal, "병원 방문 준비", "병원 예약과 문진표를 준비합니다.", LocalDateTime.now().plusDays(2));
        Task genericCompanionOnly = task(74L, personal, "누군가와 병원 동행", "아무개랑 같이가서 병원 진료를 봅니다.", LocalDateTime.now().plusDays(2));

        SearchIntent intent = SearchIntent.of(
                "친구 만나서 병원 가는 거",
                SearchQueryType.TOPIC_SEARCH,
                SearchTargetType.TASK,
                SearchDomainType.UNKNOWN,
                SearchActionIntent.UNKNOWN,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                SearchTimeIntent.UNSPECIFIED,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.ALLOW_PARTIAL,
                0.76d,
                Map.of("topicTerms", 0.2d, "mainAction", 0.3d, "participantTerms", 0.2d, "locationTerms", 0.2d),
                List.of()
        );

        when(taskSearchIntentParser.parse("친구 만나서 병원 가는 거")).thenReturn(intent);
        when(taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(1L)).thenReturn(List.of(combined, friendOnly, hospitalOnly, genericCompanionOnly));
        doNothing().when(taskSearchEmbeddingService).ensureEmbeddings(List.of(combined, friendOnly, hospitalOnly, genericCompanionOnly));
        when(taskSearchEmbeddingService.searchSimilarities(any())).thenReturn(new SemanticSearchResult(Map.of(
                70L, 0.86d,
                71L, 0.78d,
                72L, 0.79d,
                74L, 0.82d
        ), SemanticSearchStatus.READY));
        when(taskSyncStateResolver.resolve(combined)).thenReturn(snapshot(combined, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(friendOnly)).thenReturn(snapshot(friendOnly, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(hospitalOnly)).thenReturn(snapshot(hospitalOnly, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(genericCompanionOnly)).thenReturn(snapshot(genericCompanionOnly, TaskSyncState.SYNC_DISABLED));

        ProjectTaskSearchResponse response = service.search("친구 만나서 병원 가는 거");

        assertTrue(!response.isIntentFallback());
        assertEquals("RELATIONAL_SEARCH", response.getIntent().getQueryType());
        assertEquals("VISIT", response.getIntent().getMainAction());
        assertTrue(response.getIntent().getSecondaryActions().contains("MEET"));
        assertTrue(response.getIntent().getParticipantTerms().contains("친구"));
        assertTrue(response.getIntent().getLocationTerms().contains("병원"));
        assertEquals(1, response.getTaskResults().size());
        assertEquals("친구와 병원 동행", response.getTaskResults().get(0).getTitle());
    }

    @Test
    @DisplayName("search_genericCompanion질의는_특정이름이없어도_동행증거가있는일정을찾는다")
    void search_matchesGenericCompanionQueriesUsingCompanionEvidence() {
        Project personal = project(62L, "Personal");

        Task genericCompanion = task(75L, personal, "치과 방문 준비", "개인 병원 방문 일정입니다. 진료 전 필요한 문진표를 확인합니다. 아무개랑 같이가서.", LocalDateTime.now().plusDays(1));
        Task specificCompanion = task(76L, personal, "친구와 병원 동행", "친구와 함께 병원 진료를 보러 갑니다.", LocalDateTime.now().plusDays(2));
        Task soloHospital = task(77L, personal, "병원 방문 준비", "병원 예약과 문진표를 준비합니다.", LocalDateTime.now().plusDays(2));

        SearchIntent intent = SearchIntent.of(
                "누군가와 병원 가기",
                SearchQueryType.TOPIC_SEARCH,
                SearchTargetType.TASK,
                SearchDomainType.UNKNOWN,
                SearchActionIntent.UNKNOWN,
                List.of(),
                List.of(),
                List.of("누군가"),
                List.of(),
                SearchTimeIntent.UNSPECIFIED,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.ALLOW_PARTIAL,
                0.74d,
                Map.of("participantTerms", 0.3d, "locationTerms", 0.2d, "mainAction", 0.2d),
                List.of()
        );

        when(taskSearchIntentParser.parse("누군가와 병원 가기")).thenReturn(intent);
        when(taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(1L)).thenReturn(List.of(genericCompanion, specificCompanion, soloHospital));
        doNothing().when(taskSearchEmbeddingService).ensureEmbeddings(List.of(genericCompanion, specificCompanion, soloHospital));
        when(taskSearchEmbeddingService.searchSimilarities(any())).thenReturn(new SemanticSearchResult(Map.of(
                75L, 0.83d,
                76L, 0.81d,
                77L, 0.79d
        ), SemanticSearchStatus.READY));
        when(taskSyncStateResolver.resolve(genericCompanion)).thenReturn(snapshot(genericCompanion, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(specificCompanion)).thenReturn(snapshot(specificCompanion, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(soloHospital)).thenReturn(snapshot(soloHospital, TaskSyncState.SYNC_DISABLED));

        ProjectTaskSearchResponse response = service.search("누군가와 병원 가기");

        assertTrue(!response.isIntentFallback());
        List<String> titles = response.getTaskResults().stream()
                .map(result -> result.getTitle())
                .collect(java.util.stream.Collectors.toList());
        assertTrue(titles.contains("치과 방문 준비"));
        assertTrue(titles.contains("친구와 병원 동행"));
        assertTrue(!titles.contains("병원 방문 준비"));
    }

    @Test
    @DisplayName("search_genericCompanion만있고_구체앵커가없으면_fallback한다")
    void search_fallsBackWhenGenericCompanionHasNoConcreteAnchor() {
        SearchIntent intent = SearchIntent.of(
                "누군가 어떤 일 하기",
                SearchQueryType.BROAD_SEARCH,
                SearchTargetType.MIXED,
                SearchDomainType.UNKNOWN,
                SearchActionIntent.UNKNOWN,
                List.of(),
                List.of(),
                List.of("누군가"),
                List.of(),
                SearchTimeIntent.UNSPECIFIED,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.ALLOW_PARTIAL,
                0.42d,
                Map.of("participantTerms", 0.3d, "mainAction", 0.2d, "topicTerms", 0.2d),
                List.of("병원 관련 일정", "친구 관련 일정", "이번 주 일정")
        );

        when(taskSearchIntentParser.parse("누군가 어떤 일 하기")).thenReturn(intent);

        ProjectTaskSearchResponse response = service.search("누군가 어떤 일 하기");

        assertTrue(response.isIntentFallback());
        verifyNoInteractions(taskRepository, taskSyncStateResolver);
    }

    @Test
    @DisplayName("search_병원가는일정은_위치기반방문질의여도_RELATIONAL로승격하지않는다")
    void search_keepsLocationVisitQueryAsTopicSearch() {
        Project life = project(61L, "Life");
        Task hospital = task(73L, life, "병원 방문 준비", "병원 예약과 문진표를 준비합니다.", LocalDateTime.now().plusDays(1));

        SearchIntent intent = SearchIntent.of(
                "병원 가는 일정",
                SearchQueryType.TOPIC_SEARCH,
                SearchTargetType.TASK,
                SearchDomainType.UNKNOWN,
                SearchActionIntent.UNKNOWN,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                SearchTimeIntent.UNSPECIFIED,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.ALLOW_PARTIAL,
                0.71d,
                Map.of("mainAction", 0.2d, "locationTerms", 0.2d),
                List.of()
        );

        when(taskSearchIntentParser.parse("병원 가는 일정")).thenReturn(intent);
        when(taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(1L)).thenReturn(List.of(hospital));
        doNothing().when(taskSearchEmbeddingService).ensureEmbeddings(List.of(hospital));
        when(taskSearchEmbeddingService.searchSimilarities(any())).thenReturn(new SemanticSearchResult(Map.of(73L, 0.82d), SemanticSearchStatus.READY));
        when(taskSyncStateResolver.resolve(hospital)).thenReturn(snapshot(hospital, TaskSyncState.SYNC_DISABLED));

        ProjectTaskSearchResponse response = service.search("병원 가는 일정");

        assertTrue(!response.isIntentFallback());
        assertEquals("TOPIC_SEARCH", response.getIntent().getQueryType());
        assertEquals("VISIT", response.getIntent().getMainAction());
        assertTrue(response.getIntent().getLocationTerms().contains("병원"));
        assertEquals(1, response.getTaskResults().size());
        assertEquals("병원 방문 준비", response.getTaskResults().get(0).getTitle());
    }

    private Project project(Long id, String name) {
        Project project = Project.of(name, 1L);
        ReflectionTestUtils.setField(project, "id", id);
        return project;
    }

    private Task task(Long id, Project project, String title, String description, LocalDateTime dueAt) {
        Task task = Task.createTask(project, title, description, null, null, dueAt, true);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }

    private SummaryTaskSnapshot snapshot(Task task, TaskSyncState syncState) {
        return SummaryTaskSnapshot.of(task, syncState, null, null, null);
    }
}
