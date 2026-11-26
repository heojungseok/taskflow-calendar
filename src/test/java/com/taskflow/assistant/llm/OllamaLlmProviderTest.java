package com.taskflow.assistant.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskflow.common.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

class OllamaLlmProviderTest {

    private final OllamaLlmProvider provider =
            new OllamaLlmProvider(properties(), JsonMapper.builder().build());

    @Test
    @DisplayName("설정한 로컬 모델로 JSON 스키마를 강제하고 thinking을 끈다")
    void options() {
        OllamaChatOptions options = provider.options(
                new LlmRequest(LlmFeature.RECOMMENDATION, "지시", "본문", Map.of("type", "object"), 0.2, 20, 0.8));

        assertThat(options.getModel()).isEqualTo("gemma");
        assertThat(options.getOutputSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(options.getThinkOption()).isNotNull();
        assertThat(options.getThinkOption().toJsonValue()).isEqualTo(false);
        assertThat(options.getKeepAlive()).isEqualTo("10m");
        assertThat(options.getNumCtx()).isEqualTo(8192);
        assertThat(options.getTemperature()).isEqualTo(0.2);
        assertThat(options.getTopK()).isEqualTo(20);
        assertThat(options.getTopP()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("Ollama에 닿지 못하면 상태 코드 0의 일시 장애다")
    void unreachable() {
        LlmCallException e = provider.classify(new ResourceAccessException("Connection refused"), 10);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
        assertThat(e.getStatusCode()).isZero();
    }

    @Test
    @DisplayName("모델이 없으면(404) 설정 문제다")
    void modelMissing() {
        LlmCallException e = provider.classify(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null), 10);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_CONFIG_INVALID);
        assertThat(e.getStatusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Ollama 5xx는 일시 장애다")
    void serverError() {
        LlmCallException e = provider.classify(
                HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null), 10);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
        assertThat(e.getStatusCode()).isEqualTo(500);
    }

    private static OllamaLlmProperties properties() {
        OllamaLlmProperties properties = new OllamaLlmProperties();
        properties.setBaseUrl("http://localhost:11434");
        properties.setModel("gemma");
        properties.setKeepAlive("10m");
        properties.setNumCtx(8192);
        properties.setTimeoutSeconds(60);
        return properties;
    }
}
