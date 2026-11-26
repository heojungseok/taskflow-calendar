package com.taskflow.assistant.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.taskflow.common.ErrorCode;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import tools.jackson.databind.json.JsonMapper;

class OllamaLlmProviderTest {

    private static final LlmRequest REQUEST =
            new LlmRequest(LlmFeature.RECOMMENDATION, "지시", "본문", Map.of("type", "object"), 0.2, 20, 0.8);
    private static final String OK_BODY =
            "{\"model\":\"gemma\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"a\\\":1}\"},"
                    + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":1,\"eval_count\":1}";

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

    @Nested
    @DisplayName("fake 서버 호출")
    class Http {

        private FakeUpstreamServer server;

        @BeforeEach
        void setUp() throws IOException {
            server = new FakeUpstreamServer();
        }

        @AfterEach
        void tearDown() {
            server.close();
        }

        @Test
        @DisplayName("응답을 읽는다")
        void reads() {
            server.respond(200, OK_BODY);

            LlmResponse response = providerAt(server.baseUrl()).generate(REQUEST);

            assertThat(response.text()).isEqualTo("{\"a\":1}");
            assertThat(server.lastPath()).isEqualTo("/api/chat");
        }

        @Test
        @DisplayName("모델이 없으면(404) 상태 코드를 지닌 설정 문제다")
        void modelMissing() {
            server.respond(404, "{\"error\":\"model 'x' not found\"}");

            LlmCallException e = fail(providerAt(server.baseUrl()));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_CONFIG_INVALID);
            assertThat(e.getStatusCode()).isEqualTo(404);
            assertThat(e.isTemporary()).isFalse();
        }

        @Test
        @DisplayName("Ollama 5xx는 상태 코드를 지닌 일시 장애다")
        void serverError() {
            server.respond(500, "{\"error\":\"boom\"}");

            LlmCallException e = fail(providerAt(server.baseUrl()));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
            assertThat(e.getStatusCode()).isEqualTo(500);
        }

        @Test
        @DisplayName("Ollama에 닿지 못하면 상태 코드 0의 일시 장애다")
        void unreachable() {
            String baseUrl = server.baseUrl();
            server.close();

            LlmCallException e = fail(providerAt(baseUrl));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
            assertThat(e.getStatusCode()).isZero();
        }

        @Test
        @DisplayName("만들 때는 Ollama에 접속하지 않는다")
        void noNetworkAtConstruction() {
            providerAt(server.baseUrl());

            assertThat(server.hits()).isZero();
        }

        private LlmCallException fail(OllamaLlmProvider target) {
            return catchThrowableOfType(LlmCallException.class, () -> target.generate(REQUEST));
        }
    }

    private static OllamaLlmProvider providerAt(String baseUrl) {
        OllamaLlmProperties properties = properties();
        properties.setBaseUrl(baseUrl);
        properties.setTimeoutSeconds(5);
        return new OllamaLlmProvider(properties, JsonMapper.builder().build());
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
