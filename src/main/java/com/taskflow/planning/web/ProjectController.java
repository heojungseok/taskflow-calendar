package com.taskflow.planning.web;

import com.taskflow.common.ApiResponse;
import com.taskflow.planning.project.ProjectService;
import com.taskflow.planning.project.dto.CreateProjectRequest;
import com.taskflow.planning.project.dto.ProjectResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectResponse>> createProject(
            @Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse project = projectService.createProject(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(project));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectResponse>>> getAllProjects() {
        List<ProjectResponse> projects = projectService.getAllProjects();
        return ResponseEntity.ok(ApiResponse.success(projects));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ApiResponse<ProjectResponse>> getProjectById(@PathVariable Long projectId) {
        ProjectResponse project = projectService.getProjectById(projectId);
        return ResponseEntity.ok(ApiResponse.success(project));
    }
}
