package com.taskflow.assistant.search.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskflow.assistant.llm.FakeLlmProvider;
import com.taskflow.assistant.llm.LlmCallException;
import com.taskflow.assistant.llm.LlmResponse;
import com.taskflow.assistant.search.GeminiSearchProperties;
import com.taskflow.assistant.search.SearchIntent;
import com.taskflow.assistant.search.SearchTargetType;
import com.taskflow.assistant.search.exception.TaskSearchGenerationException;
import com.taskflow.common.ErrorCode;
import com.taskflow.observability.TaskFlowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LlmTaskSearchIntentParserTest {

    private final FakeLlmProvider provider = FakeLlmProvider.returning("{\"targetType\":\"task\"}\n```");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmTaskSearchIntentParser parser = new LlmTaskSearchIntentParser(
            new GeminiSearchProperties(), new ObjectMapper(), new TaskFlowMetrics(registry), provider.client());

    @Test
    @DisplayName("응답 텍스트를 JSON으로 읽어 검색 의도로 정규화하고 search_intent 지표를 남긴다")
    void parsesIntent() {
        SearchIntent intent = parser.parse("이번 주 배포");

        assertThat(intent.getTargetType()).isEqualTo(SearchTargetType.TASK);
        assertThat(registry.get("gemini_calls_total")
                        .tags("feature", "search_intent", "provider", "gemini")
                        .tag("outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("응답 본문이 비어 있으면 LLM_INVALID_RESPONSE로 실패한다")
    void blankTextFails() {
        provider.respondWith(new LlmResponse("", "m", "SAFETY", 0, 0, 0));

        assertThatThrownBy(() -> parser.parse("배포"))
                .isInstanceOfSatisfying(
                        TaskSearchGenerationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_INVALID_RESPONSE));
    }

    @Test
    @DisplayName("JSON이 아닌 응답은 LLM_INVALID_RESPONSE로 실패한다")
    void invalidJsonFails() {
        provider.respondWith(new LlmResponse("not json", "m", "STOP", 0, 0, 0));

        assertThatThrownBy(() -> parser.parse("배포"))
                .isInstanceOfSatisfying(
                        TaskSearchGenerationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_INVALID_RESPONSE));
    }

    @Test
    @DisplayName("제공자 오류는 같은 ErrorCode로 넘긴다")
    void mapsProviderFailure() {
        provider.failWith(new LlmCallException(
                ErrorCode.LLM_RATE_LIMITED_TEMPORARY, "limited", 429, 10, null, null, null, List.of()));

        assertThatThrownBy(() -> parser.parse("배포"))
                .isInstanceOfSatisfying(
                        TaskSearchGenerationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_RATE_LIMITED_TEMPORARY));
    }
}
