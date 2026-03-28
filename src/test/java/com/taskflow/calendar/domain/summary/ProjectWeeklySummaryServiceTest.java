package com.taskflow.calendar.domain.summary;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.project.exception.ProjectNotFoundException;
import com.taskflow.calendar.domain.summary.cache.WeeklySummaryCacheService;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryCacheStatus;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummarySectionResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResult;
import com.taskflow.calendar.domain.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.calendar.domain.summary.exception.WeeklySummaryGenerationException;
import com.taskflow.calendar.domain.summary.generator.WeeklySummaryGenerator;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.config.GeminiSummaryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectWeeklySummaryServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private WeeklySummaryGenerator weeklySummaryGenerator;

    @Mock
    private TaskSyncStateResolver taskSyncStateResolver;

    @Mock
    private WeeklySummaryCacheService weeklySummaryCacheService;

    private ProjectWeeklySummaryService service;

    private Project project;

    @BeforeEach
    void setUp() {
        GeminiSummaryProperties geminiProperties = new GeminiSummaryProperties();
        geminiProperties.setModel("gemini-2.5-flash");
        service = new ProjectWeeklySummaryService(
                projectRepository,
                taskRepository,
                weeklySummaryGenerator,
                taskSyncStateResolver,
                weeklySummaryCacheService,
                geminiProperties
        );
        project = Project.of("TaskFlow");
    }

    @Test
    @DisplayName("generateWeeklySummary_프로젝트없음_예외발생")
    void generateWeeklySummary_projectNotFound() {
        when(projectRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.generateWeeklySummary(1L));
    }

    @Test
    @DisplayName("generateWeeklySummary_Task없음_로컬빈요약반환")
    void generateWeeklySummary_noTasks_returnsEmptySummary() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of());

        WeeklySummaryResponse response = service.generateWeeklySummary(1L);

        assertEquals("이번 주에 Google Calendar에 반영된 일정이 없습니다.", response.getSynced().getSummary());
        assertEquals("이번 주에 아직 Google Calendar에 반영되지 않은 일정은 없습니다.", response.getUnsynced().getSummary());
        assertEquals(0, response.getTotalTaskCount());
        assertEquals(0, response.getSyncedTaskCount());
        assertEquals(0, response.getUnsyncedTaskCount());
        assertEquals("local-empty-state", response.getSynced().getModel());
        assertEquals(WeeklySummaryCacheStatus.LIVE, response.getCacheStatus());
        verify(weeklySummaryGenerator, never()).generate(any(), any(), anyInt(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("generateWeeklySummary_동기화여부별로분리하여요약반환")
    void generateWeeklySummary_withTasks_returnsGroupedSummary() {
        Task syncedTask = task("API 설계", TaskStatus.IN_PROGRESS, LocalDateTime.now().plusDays(1), true, "evt-123");
        Task unsyncedTask = task("문서 정리", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(3), false, null);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(syncedTask, unsyncedTask));
        when(taskSyncStateResolver.resolve(syncedTask))
                .thenReturn(snapshot(syncedTask, TaskSyncState.SYNCED));
        when(taskSyncStateResolver.resolve(unsyncedTask))
                .thenReturn(snapshot(unsyncedTask, TaskSyncState.SYNC_DISABLED));
        when(weeklySummaryGenerator.generate(
                eq(project),
                argThat(tasks -> tasks.size() == 1 && "API 설계".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                argThat(tasks -> tasks.size() == 1 && "문서 정리".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                any(),
                any()
        )).thenReturn(WeeklySummarySectionsResult.of(
                WeeklySummaryResult.of(
                        "이번 주에는 캘린더에 반영된 API 설계 일정을 중심으로 진행합니다.",
                        List.of("API 설계 마무리"),
                        List.of("API 설계 일정이 밀리면 후속 일정이 지연될 수 있습니다."),
                        List.of("API 설계 검토를 완료하세요."),
                        "gemini-2.5-flash"
                ),
                WeeklySummaryResult.of(
                        "이번 주에는 아직 캘린더에 반영되지 않은 문서 정리 Task가 남아 있습니다.",
                        List.of("문서 정리 일정 확정 필요"),
                        List.of("문서 정리가 누락되면 공유 일정이 불명확해질 수 있습니다."),
                        List.of("문서 정리 일정을 캘린더에 반영하세요."),
                        "gemini-2.5-flash"
                )
        ));

        WeeklySummaryResponse response = service.generateWeeklySummary(1L);

        verify(weeklySummaryGenerator).generate(
                eq(project),
                argThat(tasks -> tasks.size() == 1 && "API 설계".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                argThat(tasks -> tasks.size() == 1 && "문서 정리".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                any(),
                any()
        );

        assertEquals(2, response.getTotalTaskCount());
        assertEquals(1, response.getSyncedTaskCount());
        assertEquals(1, response.getUnsyncedTaskCount());
        assertEquals("이번 주에는 캘린더에 반영된 API 설계 일정을 중심으로 진행합니다.", response.getSynced().getSummary());
        assertEquals("이번 주에는 아직 캘린더에 반영되지 않은 문서 정리 Task가 남아 있습니다.", response.getUnsynced().getSummary());
        assertEquals(WeeklySummaryCacheStatus.LIVE, response.getCacheStatus());
    }

    @Test
    @DisplayName("generateWeeklySummary_이벤트아이디없으면_미동기화로분류")
    void generateWeeklySummary_withoutEventId_goesToUnsynced() {
        Task pendingSyncTask = task("캘린더 대기", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(1), true, null);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(pendingSyncTask));
        when(taskSyncStateResolver.resolve(pendingSyncTask))
                .thenReturn(snapshot(pendingSyncTask, TaskSyncState.PENDING_SYNC));
        when(weeklySummaryGenerator.generate(
                eq(project),
                argThat(List::isEmpty),
                eq(0),
                argThat(tasks -> tasks.size() == 1 && "캘린더 대기".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                any(),
                any()
        )).thenReturn(WeeklySummarySectionsResult.of(
                null,
                WeeklySummaryResult.of(
                        "캘린더 반영 대기 중인 Task가 있습니다.",
                        List.of(),
                        List.of("일정 누락 가능성이 있습니다."),
                        List.of("캘린더 동기화 상태를 확인하세요."),
                        "gemini-2.5-flash"
                )
        ));

        WeeklySummaryResponse response = service.generateWeeklySummary(1L);

        verify(weeklySummaryGenerator).generate(
                eq(project),
                argThat(List::isEmpty),
                eq(0),
                argThat(tasks -> tasks.size() == 1 && "캘린더 대기".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                any(),
                any()
        );
        assertEquals(0, response.getSyncedTaskCount());
        assertEquals(1, response.getUnsyncedTaskCount());
    }

    @Test
    @DisplayName("generateWeeklySummary_startAt기반이벤트는_dueAt이멀어도우선반영")
    void generateWeeklySummary_eventWindowPriority_withStartAt() {
        Task rangedTask = Task.createTask(
                project,
                "이번주 착수-장기 일정",
                "이번 주 착수하지만 마감은 다음 달 일정",
                null,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(20),
                false
        );
        Task noStartTask = Task.createTask(
                project,
                "마감만 먼 일정",
                "이번 주 이벤트 신호가 없는 일정",
                null,
                null,
                LocalDateTime.now().plusDays(20),
                false
        );

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(noStartTask, rangedTask));
        when(taskSyncStateResolver.resolve(rangedTask))
                .thenReturn(snapshot(rangedTask, TaskSyncState.SYNC_DISABLED));
        when(taskSyncStateResolver.resolve(noStartTask))
                .thenReturn(snapshot(noStartTask, TaskSyncState.SYNC_DISABLED));
        when(weeklySummaryGenerator.generate(
                eq(project),
                argThat(List::isEmpty),
                eq(0),
                any(),
                eq(2),
                any(),
                any()
        )).thenReturn(WeeklySummarySectionsResult.of(
                null,
                WeeklySummaryResult.of("요약", List.of(), List.of(), List.of(), "gemini-2.5-flash")
        ));

        service.generateWeeklySummary(1L);

        verify(weeklySummaryGenerator).generate(
                eq(project),
                argThat(List::isEmpty),
                eq(0),
                argThat(tasks -> tasks.size() == 2
                        && "이번주 착수-장기 일정".equals(tasks.get(0).getTask().getTitle())
                        && "마감만 먼 일정".equals(tasks.get(1).getTask().getTitle())),
                eq(2),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("generateWeeklySummary_최근수정설명Task는_우선순위정렬에가산된다")
    void generateWeeklySummary_recentUpdatePriorityBoost() throws Exception {
        Task recentDescribed = task("최근 수정 일정", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(9), true, "evt-a");
        Task staleDescribed = task("기존 일정", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(9), true, "evt-b");

        setField(recentDescribed, "description", "리스크와 의존성이 있는 최신 수정 설명");
        setField(staleDescribed, "description", "리스크와 의존성이 있는 기존 설명");
        setField(recentDescribed, "updatedAt", LocalDateTime.now().minusHours(1));
        setField(staleDescribed, "updatedAt", LocalDateTime.now().minusHours(48));

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(staleDescribed, recentDescribed));
        when(taskSyncStateResolver.resolve(recentDescribed)).thenReturn(snapshot(recentDescribed, TaskSyncState.SYNCED));
        when(taskSyncStateResolver.resolve(staleDescribed)).thenReturn(snapshot(staleDescribed, TaskSyncState.SYNCED));
        when(weeklySummaryGenerator.generate(
                eq(project),
                any(),
                eq(2),
                argThat(List::isEmpty),
                eq(0),
                any(),
                any()
        )).thenReturn(WeeklySummarySectionsResult.of(
                WeeklySummaryResult.of("요약", List.of(), List.of(), List.of(), "gemini-2.5-flash"),
                null
        ));

        service.generateWeeklySummary(1L);

        verify(weeklySummaryGenerator).generate(
                eq(project),
                argThat(tasks -> tasks.size() == 2
                        && "최근 수정 일정".equals(tasks.get(0).getTask().getTitle())
                        && "기존 일정".equals(tasks.get(1).getTask().getTitle())),
                eq(2),
                argThat(List::isEmpty),
                eq(0),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("generateWeeklySummary_동일입력이면_캐시응답반환")
    void generateWeeklySummary_cacheHit_returnsCachedResponse() {
        Task cachedTask = task("캐시된 일정", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(1), false, null);
        WeeklySummaryResponse cachedResponse = cachedResponse(WeeklySummaryCacheStatus.LIVE);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(cachedTask));
        when(taskSyncStateResolver.resolve(cachedTask)).thenReturn(snapshot(cachedTask, TaskSyncState.SYNC_DISABLED));
        when(weeklySummaryCacheService.isEnabled()).thenReturn(true);
        when(weeklySummaryCacheService.find(argThat(key -> key != null && key.startsWith("weekly-summary:v1:exact:"))))
                .thenReturn(Optional.of(cachedResponse));

        WeeklySummaryResponse response = service.generateWeeklySummary(1L);

        assertEquals(WeeklySummaryCacheStatus.CACHE_HIT, response.getCacheStatus());
        verify(weeklySummaryGenerator, never()).generate(any(), any(), anyInt(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("generateWeeklySummary_forceLive면_exact캐시를조회하지않고_live생성")
    void generateWeeklySummary_forceLive_skipsExactCacheRead() {
        Task liveTask = task("라이브 일정", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(1), false, null);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(liveTask));
        when(taskSyncStateResolver.resolve(liveTask)).thenReturn(snapshot(liveTask, TaskSyncState.SYNC_DISABLED));
        when(weeklySummaryCacheService.isEnabled()).thenReturn(true);
        when(weeklySummaryGenerator.generate(
                eq(project),
                argThat(List::isEmpty),
                eq(0),
                argThat(tasks -> tasks.size() == 1 && "라이브 일정".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                any(),
                any()
        )).thenReturn(WeeklySummarySectionsResult.of(
                null,
                WeeklySummaryResult.of("라이브 요약", List.of(), List.of(), List.of(), "gemini-2.5-flash")
        ));

        WeeklySummaryResponse response = service.generateWeeklySummary(1L, true);

        assertEquals(WeeklySummaryCacheStatus.LIVE, response.getCacheStatus());
        verify(weeklySummaryCacheService, never()).find(argThat(key -> key != null && key.startsWith("weekly-summary:v1:exact:")));
        verify(weeklySummaryGenerator).generate(
                eq(project),
                argThat(List::isEmpty),
                eq(0),
                argThat(tasks -> tasks.size() == 1 && "라이브 일정".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("generateWeeklySummary_quota소진시_최신캐시로fallback")
    void generateWeeklySummary_quotaExhausted_returnsStaleFallback() {
        Task task = task("Quota 일정", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(1), false, null);
        WeeklySummaryResponse cachedResponse = cachedResponse(WeeklySummaryCacheStatus.LIVE);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(task));
        when(taskSyncStateResolver.resolve(task)).thenReturn(snapshot(task, TaskSyncState.SYNC_DISABLED));
        when(weeklySummaryCacheService.isEnabled()).thenReturn(true);
        when(weeklySummaryCacheService.find(argThat(key -> key != null && key.startsWith("weekly-summary:v1:exact:"))))
                .thenReturn(Optional.empty());
        when(weeklySummaryCacheService.find(argThat(key -> key != null && key.startsWith("weekly-summary:v1:latest:"))))
                .thenReturn(Optional.of(cachedResponse));
        when(weeklySummaryGenerator.generate(
                eq(project),
                argThat(List::isEmpty),
                eq(0),
                argThat(tasks -> tasks.size() == 1 && "Quota 일정".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                any(),
                any()
        ))
                .thenThrow(new WeeklySummaryGenerationException(
                        com.taskflow.common.ErrorCode.LLM_QUOTA_EXHAUSTED,
                        "quota exceeded",
                        true
                ));

        WeeklySummaryResponse response = service.generateWeeklySummary(1L);

        assertEquals(WeeklySummaryCacheStatus.STALE_FALLBACK, response.getCacheStatus());
        assertEquals("캐시 요약", response.getUnsynced().getSummary());
    }

    @Test
    @DisplayName("generateWeeklySummary_임시rateLimit시_최신캐시로fallback")
    void generateWeeklySummary_temporaryRateLimit_returnsStaleFallback() {
        Task task = task("Rate limit 일정", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(1), false, null);
        WeeklySummaryResponse cachedResponse = cachedResponse(WeeklySummaryCacheStatus.LIVE);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(task));
        when(taskSyncStateResolver.resolve(task)).thenReturn(snapshot(task, TaskSyncState.SYNC_DISABLED));
        when(weeklySummaryCacheService.isEnabled()).thenReturn(true);
        when(weeklySummaryCacheService.find(argThat(key -> key != null && key.startsWith("weekly-summary:v1:exact:"))))
                .thenReturn(Optional.empty());
        when(weeklySummaryCacheService.find(argThat(key -> key != null && key.startsWith("weekly-summary:v1:latest:"))))
                .thenReturn(Optional.of(cachedResponse));
        when(weeklySummaryGenerator.generate(
                eq(project),
                argThat(List::isEmpty),
                eq(0),
                argThat(tasks -> tasks.size() == 1 && "Rate limit 일정".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                any(),
                any()
        ))
                .thenThrow(new WeeklySummaryGenerationException(
                        com.taskflow.common.ErrorCode.LLM_RATE_LIMITED_TEMPORARY,
                        "rate limited",
                        true
                ));

        WeeklySummaryResponse response = service.generateWeeklySummary(1L);

        assertEquals(WeeklySummaryCacheStatus.STALE_FALLBACK, response.getCacheStatus());
        assertEquals("캐시 요약", response.getUnsynced().getSummary());
    }

    @Test
    @DisplayName("generateWeeklySummary_unknown429시_최신캐시로fallback")
    void generateWeeklySummary_unknown429_returnsStaleFallback() {
        Task task = task("Unknown 일정", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(1), false, null);
        WeeklySummaryResponse cachedResponse = cachedResponse(WeeklySummaryCacheStatus.LIVE);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(task));
        when(taskSyncStateResolver.resolve(task)).thenReturn(snapshot(task, TaskSyncState.SYNC_DISABLED));
        when(weeklySummaryCacheService.isEnabled()).thenReturn(true);
        when(weeklySummaryCacheService.find(argThat(key -> key != null && key.startsWith("weekly-summary:v1:exact:"))))
                .thenReturn(Optional.empty());
        when(weeklySummaryCacheService.find(argThat(key -> key != null && key.startsWith("weekly-summary:v1:latest:"))))
                .thenReturn(Optional.of(cachedResponse));
        when(weeklySummaryGenerator.generate(
                eq(project),
                argThat(List::isEmpty),
                eq(0),
                argThat(tasks -> tasks.size() == 1 && "Unknown 일정".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                any(),
                any()
        ))
                .thenThrow(new WeeklySummaryGenerationException(
                        com.taskflow.common.ErrorCode.LLM_429_UNKNOWN,
                        "429 unknown",
                        true
                ));

        WeeklySummaryResponse response = service.generateWeeklySummary(1L);

        assertEquals(WeeklySummaryCacheStatus.STALE_FALLBACK, response.getCacheStatus());
        assertEquals("캐시 요약", response.getUnsynced().getSummary());
    }

    @Test
    @DisplayName("generateWeeklySummary_forceLive면_429실패시_latest캐시fallback을사용하지않는다")
    void generateWeeklySummary_forceLive_doesNotUseLatestFallback() {
        Task task = task("Force live 일정", TaskStatus.REQUESTED, LocalDateTime.now().plusDays(1), false, null);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProjectIdAndDeletedFalse(1L)).thenReturn(List.of(task));
        when(taskSyncStateResolver.resolve(task)).thenReturn(snapshot(task, TaskSyncState.SYNC_DISABLED));
        when(weeklySummaryGenerator.generate(
                eq(project),
                argThat(List::isEmpty),
                eq(0),
                argThat(tasks -> tasks.size() == 1 && "Force live 일정".equals(tasks.get(0).getTask().getTitle())),
                eq(1),
                any(),
                any()
        ))
                .thenThrow(new WeeklySummaryGenerationException(
                        com.taskflow.common.ErrorCode.LLM_429_UNKNOWN,
                        "429 unknown",
                        true
                ));

        assertThrows(WeeklySummaryGenerationException.class, () -> service.generateWeeklySummary(1L, true));
        verify(weeklySummaryCacheService, never()).find(argThat(key -> key != null && key.startsWith("weekly-summary:v1:latest:")));
    }

    private Task task(String title, TaskStatus status, LocalDateTime dueAt, boolean calendarSyncEnabled, String eventId) {
        Task task = Task.createTask(project, title, "설명", null, null, dueAt, calendarSyncEnabled);
        if (status != TaskStatus.REQUESTED) {
            task.changeStatus(status);
        }
        if (eventId != null) {
            task.setCalendarEventId(eventId);
        }
        return task;
    }

    private SummaryTaskSnapshot snapshot(Task task, TaskSyncState syncState) {
        return SummaryTaskSnapshot.of(task, syncState, null, null, null);
    }

    private WeeklySummaryResponse cachedResponse(WeeklySummaryCacheStatus cacheStatus) {
        return WeeklySummaryResponse.of(
                project,
                java.time.LocalDate.now(),
                java.time.LocalDate.now().plusDays(6),
                LocalDateTime.now().minusHours(1),
                cacheStatus,
                1,
                0,
                1,
                WeeklySummarySectionResponse.of(0, 0, WeeklySummaryResult.empty("빈 동기화", List.of())),
                WeeklySummarySectionResponse.of(1, 1, WeeklySummaryResult.of(
                        "캐시 요약",
                        List.of("캐시 포인트"),
                        List.of(),
                        List.of("캐시 행동"),
                        "gemini-2.5-flash"
                ))
        );
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
