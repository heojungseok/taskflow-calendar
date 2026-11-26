package com.taskflow.assistant.recommendation.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskflow.assistant.recommendation.dto.ProjectTaskRecommendationItemResponse;
import com.taskflow.assistant.recommendation.dto.ProjectTaskRecommendationResponse;
import com.taskflow.assistant.recommendation.dto.TaskRecommendationCacheStatus;
import com.taskflow.planning.project.Project;
import com.taskflow.planning.task.TaskStatus;
import com.taskflow.planning.task.TaskSyncState;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RecommendationCacheJsonTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("Redis에 쓴 추천 응답을 다시 읽으면 같은 값이 나온다")
    void roundTrip() {
        ProjectTaskRecommendationResponse response = ProjectTaskRecommendationResponse.of(
                Project.of("TaskFlow", 1L),
                Instant.parse("2026-11-26T09:00:00Z"),
                TaskRecommendationCacheStatus.LIVE,
                3,
                2,
                List.of(ProjectTaskRecommendationItemResponse.of(
                        10L,
                        1,
                        90,
                        "배포",
                        TaskStatus.REQUESTED,
                        LocalDateTime.of(2026, 11, 27, 18, 0),
                        true,
                        "event-1",
                        TaskSyncState.SYNCED,
                        "마감 임박",
                        null,
                        "내일 마감입니다")));

        String json = objectMapper.writeValueAsString(response);
        ProjectTaskRecommendationResponse read = objectMapper.readValue(json, ProjectTaskRecommendationResponse.class);

        assertThat(objectMapper.writeValueAsString(read)).isEqualTo(json);
    }

    @Test
    @DisplayName("이전 버전(Jackson 2)이 쓴 추천 캐시 값도 읽는다")
    void readsJackson2Value() {
        String jackson2 = """
                {"projectId":1,"projectName":"TaskFlow","generatedAt":"2026-11-26T09:00:00Z","cacheStatus":"LIVE",\
                "totalEligibleTaskCount":3,"candidateCount":2,"recommendedCount":1,"items":[{"taskId":10,"rank":1,\
                "score":90,"title":"배포","status":"REQUESTED","dueAt":"2026-11-27T18:00:00",\
                "calendarSyncEnabled":true,"calendarEventId":"event-1","syncState":"SYNCED","primaryTag":"마감 임박",\
                "secondaryTag":null,"reason":"내일 마감입니다"}]}""";

        ProjectTaskRecommendationResponse read =
                objectMapper.readValue(jackson2, ProjectTaskRecommendationResponse.class);

        assertThat(read.getItems()).hasSize(1);
        assertThat(read.getItems().getFirst().getDueAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 18, 0));
    }
}
