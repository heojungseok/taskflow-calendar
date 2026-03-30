package com.taskflow.web;

import com.taskflow.calendar.domain.search.ProjectTaskSearchService;
import com.taskflow.calendar.domain.search.dto.ProjectTaskSearchResponse;
import com.taskflow.calendar.domain.search.dto.TaskSearchRequest;
import com.taskflow.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/search")
public class SearchController {

    private final ProjectTaskSearchService projectTaskSearchService;

    @PostMapping("/tasks")
    public ResponseEntity<ApiResponse<ProjectTaskSearchResponse>> searchTasks(
            @Valid @RequestBody TaskSearchRequest request
    ) {
        ProjectTaskSearchResponse response = projectTaskSearchService.search(request.getQuery().trim());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
