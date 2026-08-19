package com.taskflow.calendar.domain.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.recommendation.ProjectTaskRecommendationService;
import com.taskflow.calendar.domain.recommendation.cache.TaskRecommendationCacheService;
import com.taskflow.calendar.domain.recommendation.dto.TaskRecommendationCacheStatus;
import com.taskflow.calendar.domain.recommendation.generator.TaskRecommendationGenerator;
import com.taskflow.calendar.domain.search.ProjectTaskSearchService;
import com.taskflow.calendar.domain.search.SemanticSearchStatus;
import com.taskflow.calendar.domain.search.TaskSearchEmbeddingService;
import com.taskflow.calendar.domain.search.TaskSearchEmbeddingStore;
import com.taskflow.calendar.domain.search.generator.TaskSearchIntentParser;
import com.taskflow.calendar.domain.summary.ProjectWeeklySummaryService;
import com.taskflow.calendar.domain.summary.TaskSyncStateResolver;
import com.taskflow.calendar.domain.summary.cache.WeeklySummaryCacheService;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryCacheStatus;
import com.taskflow.calendar.domain.summary.generator.WeeklySummaryGenerator;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.config.GeminiRecommendationProperties;
import com.taskflow.config.GeminiSearchProperties;
import com.taskflow.config.GeminiSummaryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyList;

class DemoAiIsolationTest {

    private final Long userId = 1L;
    private final Long projectId = 2L;
    private final User demo = User.createDemoUser("demo", LocalDateTime.now().plusHours(1));
    private final Project project = Project.of("Demo", userId);

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void summarySkipsGeneratorAndCache() {
        ProjectRepository projects = mock(ProjectRepository.class);
        TaskRepository tasks = mock(TaskRepository.class);
        WeeklySummaryGenerator generator = mock(WeeklySummaryGenerator.class);
        TaskSyncStateResolver sync = mock(TaskSyncStateResolver.class);
        WeeklySummaryCacheService cache = mock(WeeklySummaryCacheService.class);
        UserRepository users = mock(UserRepository.class);
        given(projects.findByIdAndOwnerUserId(projectId, userId)).willReturn(Optional.of(project));
        given(tasks.findAllByProjectIdAndDeletedFalse(projectId)).willReturn(List.of());
        given(sync.resolveAll(List.of())).willReturn(List.of());
        given(users.findById(userId)).willReturn(Optional.of(demo));

        ProjectWeeklySummaryService service = new ProjectWeeklySummaryService(
                projects, tasks, generator, sync, cache, new GeminiSummaryProperties(), users);

        assertEquals(WeeklySummaryCacheStatus.DEMO_LOCAL,
                service.generateWeeklySummary(projectId).getCacheStatus());
        verifyNoInteractions(generator, cache);
    }

    @Test
    void recommendationSkipsGeneratorAndCache() {
        ProjectRepository projects = mock(ProjectRepository.class);
        TaskRepository tasks = mock(TaskRepository.class);
        TaskSyncStateResolver sync = mock(TaskSyncStateResolver.class);
        TaskRecommendationGenerator generator = mock(TaskRecommendationGenerator.class);
        TaskRecommendationCacheService cache = mock(TaskRecommendationCacheService.class);
        UserRepository users = mock(UserRepository.class);
        given(projects.findByIdAndOwnerUserId(projectId, userId)).willReturn(Optional.of(project));
        given(tasks.findAllByProjectIdAndDeletedFalse(projectId)).willReturn(List.of());
        given(users.findById(userId)).willReturn(Optional.of(demo));

        ProjectTaskRecommendationService service = new ProjectTaskRecommendationService(
                projects, tasks, sync, generator, cache, new GeminiRecommendationProperties(), users);

        assertEquals(TaskRecommendationCacheStatus.DEMO_LOCAL,
                service.getRecommendations(projectId).getCacheStatus());
        verifyNoInteractions(generator, cache);
    }

    @Test
    void searchSkipsIntentAndEmbeddingClients() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskSyncStateResolver sync = mock(TaskSyncStateResolver.class);
        TaskSearchIntentParser parser = mock(TaskSearchIntentParser.class);
        TaskSearchEmbeddingService embeddings = mock(TaskSearchEmbeddingService.class);
        UserRepository users = mock(UserRepository.class);
        given(users.findById(userId)).willReturn(Optional.of(demo));
        given(tasks.findAllByDeletedFalseAndProject_OwnerUserId(userId)).willReturn(List.of());
        given(sync.resolveAll(List.of())).willReturn(List.of());

        ProjectTaskSearchService service = new ProjectTaskSearchService(
                tasks, sync, parser, embeddings, users);

        assertEquals(SemanticSearchStatus.DISABLED, service.search("데모").getSemanticStatus());
        verifyNoInteractions(parser, embeddings);
    }

    @Test
    void afterCommitEmbeddingRefreshSkipsDemoOwner() {
        GeminiSearchProperties properties = new GeminiSearchProperties();
        properties.setSemanticEnabled(true);
        properties.setApiKey("test-key");
        TaskSearchEmbeddingStore store = mock(TaskSearchEmbeddingStore.class);
        TaskRepository tasks = mock(TaskRepository.class);
        UserRepository users = mock(UserRepository.class);
        Task task = Task.createTask(project, "Demo", null, null, null, null, false);
        given(store.isAvailable()).willReturn(true);
        given(users.findById(userId)).willReturn(Optional.of(demo));

        TaskSearchEmbeddingService service = new TaskSearchEmbeddingService(
                properties, mock(ObjectMapper.class), store, tasks, users);

        service.ensureEmbeddings(List.of(task));

        verify(store, never()).findHashesByTaskIds(anyList());
    }
}
