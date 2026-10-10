package com.taskflow.assistant.web;

import com.taskflow.assistant.recommendation.ProjectTaskRecommendationService;
import com.taskflow.assistant.recommendation.dto.ProjectTaskRecommendationResponse;
import com.taskflow.assistant.summary.ProjectWeeklySummaryService;
import com.taskflow.assistant.summary.cache.WeeklySummaryCacheService;
import com.taskflow.assistant.summary.dto.WeeklySummaryCacheHealthResponse;
import com.taskflow.assistant.summary.dto.WeeklySummaryResponse;
import com.taskflow.common.ApiResponse;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;
import com.taskflow.identity.security.SecurityContextHelper;
import com.taskflow.identity.user.Provider;
import com.taskflow.identity.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 프로젝트 단위 LLM 기능(주간 요약, 할 일 추천). 경로는 ProjectController와 같은 /api/projects 아래에 둔다. */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectAssistantController {

    private final ProjectWeeklySummaryService projectWeeklySummaryService;
    private final ProjectTaskRecommendationService projectTaskRecommendationService;
    private final WeeklySummaryCacheService weeklySummaryCacheService;
    private final UserRepository userRepository;

    @Value("${summary.force-live-enabled:false}")
    private boolean forceLiveEnabled;

    @PostMapping("/{projectId}/weekly-summary")
    public ResponseEntity<ApiResponse<WeeklySummaryResponse>> generateWeeklySummary(
            @PathVariable Long projectId, @RequestParam(defaultValue = "false") boolean forceLive) {
        if (forceLive && !forceLiveEnabled) {
            throw new BusinessException(
                    ErrorCode.WEEKLY_SUMMARY_FORCE_LIVE_DISABLED, "forceLive is disabled for this environment.");
        }
        WeeklySummaryResponse summary = projectWeeklySummaryService.generateWeeklySummary(projectId, forceLive);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/{projectId}/task-recommendations")
    public ResponseEntity<ApiResponse<ProjectTaskRecommendationResponse>> getTaskRecommendations(
            @PathVariable Long projectId) {
        ProjectTaskRecommendationResponse response = projectTaskRecommendationService.getRecommendations(projectId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/weekly-summary/cache-health")
    public ResponseEntity<ApiResponse<WeeklySummaryCacheHealthResponse>> checkWeeklySummaryCacheHealth() {
        WeeklySummaryCacheHealthResponse health = userRepository
                .findById(SecurityContextHelper.getCurrentUserId())
                .filter(user -> user.getProvider() == Provider.DEMO)
                .map(user -> WeeklySummaryCacheHealthResponse.disabled())
                .orElseGet(weeklySummaryCacheService::healthCheck);
        HttpStatus status = health.isHealthy() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(ApiResponse.success(health));
    }
}
