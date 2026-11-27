package com.taskflow.assistant.recommendation.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskflow.assistant.llm.FakeLlmProvider;
import com.taskflow.assistant.llm.LlmCallException;
import com.taskflow.assistant.llm.LlmFeature;
import com.taskflow.assistant.llm.LlmRequest;
import com.taskflow.assistant.recommendation.GeminiRecommendationProperties;
import com.taskflow.assistant.recommendation.exception.TaskRecommendationGenerationException;
import com.taskflow.common.ErrorCode;
import com.taskflow.observability.TaskFlowMetrics;
import com.taskflow.planning.project.Project;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LlmTaskRecommendationGeneratorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    private final FakeLlmProvider provider = FakeLlmProvider.returning("{}");
    private final GeminiRecommendationProperties properties = new GeminiRecommendationProperties();
    private final LlmTaskRecommendationGenerator generator = new LlmTaskRecommendationGenerator(
            properties, new ObjectMapper(), new TaskFlowMetrics(new SimpleMeterRegistry()), provider.client());

    @Test
    @DisplayName("추천 기능, 온도, 한국어 지시, 응답 스키마를 요청에 담는다")
    void sendsRecommendationRequest() {
        assertThatThrownBy(() -> generate()).isInstanceOf(TaskRecommendationGenerationException.class);

        LlmRequest request = provider.lastRequest();
        assertThat(request.feature()).isEqualTo(LlmFeature.RECOMMENDATION);
        assertThat(request.temperature()).isEqualTo(properties.getTemperature());
        assertThat(request.topK()).isNull();
        assertThat(request.topP()).isNull();
        assertThat(request.systemInstruction()).startsWith("당신은 후보 Task를 비교해 우선 확인 순서를 정하는 추천 보조자다.");
        assertThat(request.userPrompt()).startsWith("아래 candidates만 비교해 지금 먼저 확인할 task를 고르라.");
        assertThat(request.responseSchema()).containsEntry("required", List.of("items"));
    }

    @Test
    @DisplayName("응답 본문이 비어 있으면 LLM_INVALID_RESPONSE로 실패한다")
    void blankTextFails() {
        provider.respondWith(new com.taskflow.assistant.llm.LlmResponse(" ", "m", "SAFETY", 0, 0, 0));

        assertThatThrownBy(() -> generate())
                .isInstanceOfSatisfying(
                        TaskRecommendationGenerationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_INVALID_RESPONSE));
    }

    @Test
    @DisplayName("제공자 오류는 같은 ErrorCode로 넘긴다")
    void mapsProviderFailure() {
        provider.failWith(new LlmCallException(
                ErrorCode.LLM_API_KEY_MISSING, "Gemini API key is not configured", 0, 0, null, null, null, List.of()));

        assertThatThrownBy(() -> generate())
                .isInstanceOfSatisfying(
                        TaskRecommendationGenerationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_API_KEY_MISSING));
    }

    private void generate() {
        generator.generate(Project.of("TaskFlow", 1L), List.of(), 1, TODAY);
    }
}
