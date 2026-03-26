package com.taskflow.web;

import com.taskflow.calendar.domain.project.ProjectService;
import com.taskflow.calendar.domain.project.dto.CreateProjectRequest;
import com.taskflow.calendar.domain.project.dto.ProjectResponse;
import com.taskflow.calendar.domain.summary.ProjectWeeklySummaryService;
import com.taskflow.calendar.domain.summary.WeeklySummaryCacheService;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryCacheHealthResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResponse;
import com.taskflow.common.ApiResponse;
import lombok.RequiredArgsConstructor;
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
    private final WeeklySummaryCacheService weeklySummaryCacheService;

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
            @PathVariable Long projectId
    ) {
        WeeklySummaryResponse summary = projectWeeklySummaryService.generateWeeklySummary(projectId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/weekly-summary/cache-health")
    public ResponseEntity<ApiResponse<WeeklySummaryCacheHealthResponse>> checkWeeklySummaryCacheHealth() {
        WeeklySummaryCacheHealthResponse health = weeklySummaryCacheService.healthCheck();
        HttpStatus status = health.isHealthy() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(ApiResponse.success(health));
    }
}
