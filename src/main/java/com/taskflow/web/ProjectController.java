package com.taskflow.web;

import com.taskflow.common.ApiResponse;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;
import com.taskflow.calendar.domain.project.ProjectService;
import com.taskflow.calendar.domain.recommendation.ProjectTaskRecommendationService;
import com.taskflow.calendar.domain.recommendation.dto.ProjectTaskRecommendationResponse;
import com.taskflow.calendar.domain.project.dto.CreateProjectRequest;
import com.taskflow.calendar.domain.project.dto.ProjectResponse;
import com.taskflow.calendar.domain.summary.ProjectWeeklySummaryService;
import com.taskflow.calendar.domain.summary.cache.WeeklySummaryCacheService;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryCacheHealthResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectWeeklySummaryService projectWeeklySummaryService;
    private final ProjectTaskRecommendationService projectTaskRecommendationService;
    private final WeeklySummaryCacheService weeklySummaryCacheService;
    @Value("${summary.force-live-enabled:false}")
    private boolean forceLiveEnabled;

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectResponse>> createProject(
            @Valid @RequestBody CreateProjectRequest request
    ) {
        ProjectResponse project = projectService.createProject(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(project));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectResponse>>> getAllProjects() {
        List<ProjectResponse> projects = projectService.getAllProjects();
        return ResponseEntity.ok(ApiResponse.success(projects));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ApiResponse<ProjectResponse>> getProjectById(
            @PathVariable Long projectId
    ) {
        ProjectResponse project = projectService.getProjectById(projectId);
        return ResponseEntity.ok(ApiResponse.success(project));
    }

    @PostMapping("/{projectId}/weekly-summary")
    public ResponseEntity<ApiResponse<WeeklySummaryResponse>> generateWeeklySummary(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "false") boolean forceLive
    ) {
        if (forceLive && !forceLiveEnabled) {
            throw new BusinessException(
                    ErrorCode.WEEKLY_SUMMARY_FORCE_LIVE_DISABLED,
                    "forceLive is disabled for this environment."
            );
        }
        WeeklySummaryResponse summary = projectWeeklySummaryService.generateWeeklySummary(projectId, forceLive);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/{projectId}/task-recommendations")
    public ResponseEntity<ApiResponse<ProjectTaskRecommendationResponse>> getTaskRecommendations(
            @PathVariable Long projectId
    ) {
        ProjectTaskRecommendationResponse response = projectTaskRecommendationService.getRecommendations(projectId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/weekly-summary/cache-health")
    public ResponseEntity<ApiResponse<WeeklySummaryCacheHealthResponse>> checkWeeklySummaryCacheHealth() {
        WeeklySummaryCacheHealthResponse health = weeklySummaryCacheService.healthCheck();
        HttpStatus status = health.isHealthy() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(ApiResponse.success(health));
    }
}
