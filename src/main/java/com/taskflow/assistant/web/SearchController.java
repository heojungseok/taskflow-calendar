package com.taskflow.assistant.web;

import com.taskflow.assistant.search.ProjectTaskSearchService;
import com.taskflow.assistant.search.dto.ProjectTaskSearchResponse;
import com.taskflow.assistant.search.dto.TaskSearchRequest;
import com.taskflow.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/search")
public class SearchController {

    private final ProjectTaskSearchService projectTaskSearchService;

    @PostMapping("/tasks")
    public ResponseEntity<ApiResponse<ProjectTaskSearchResponse>> searchTasks(
            @Valid @RequestBody TaskSearchRequest request) {
        ProjectTaskSearchResponse response =
                projectTaskSearchService.search(request.getQuery().trim());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
