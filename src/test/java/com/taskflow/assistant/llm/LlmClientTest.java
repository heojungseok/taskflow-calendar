package com.taskflow.assistant.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmClientTest {

    private static final LlmRequest SUMMARY =
            new LlmRequest(LlmFeature.WEEKLY_SUMMARY, "system", "user", Map.of("type", "object"), 0.2, null, null);

    @Test
    @DisplayName("기능별 설정이 고른 제공자로 보낸다")
    void routesByFeature() {
        RecordingProvider gemini = new RecordingProvider("gemini");
        RecordingProvider ollama = new RecordingProvider("ollama");
        LlmClient client = new LlmClient(
                List.of(gemini, ollama), new LlmRoutingProperties(Map.of(LlmFeature.WEEKLY_SUMMARY, "ollama")));

        LlmResponse response = client.generate(SUMMARY);

        assertThat(response.model()).isEqualTo("ollama-model");
        assertThat(ollama.requests).containsExactly(SUMMARY);
        assertThat(gemini.requests).isEmpty();
    }

    @Test
    @DisplayName("설정이 없는 기능은 gemini로 보낸다")
    void defaultsToGemini() {
        RecordingProvider gemini = new RecordingProvider("gemini");
        LlmClient client =
                new LlmClient(List.of(gemini, new RecordingProvider("ollama")), new LlmRoutingProperties(Map.of()));

        client.generate(SUMMARY);

        assertThat(gemini.requests).containsExactly(SUMMARY);
    }

    @Test
    @DisplayName("기능이 지금 쓰는 제공자와 모델을 캐시 키용 이름으로 알려 준다(gemini는 모델 이름만)")
    void modelIdFollowsRoute() {
        LlmClient client = new LlmClient(
                List.of(new RecordingProvider("gemini"), new RecordingProvider("ollama")),
                new LlmRoutingProperties(Map.of(LlmFeature.WEEKLY_SUMMARY, "ollama")));

        assertThat(client.modelId(LlmFeature.WEEKLY_SUMMARY)).isEqualTo("ollama:ollama-model");
        assertThat(client.modelId(LlmFeature.RECOMMENDATION)).isEqualTo("gemini-model");
    }

    @Test
    @DisplayName("기능이 지금 쓰는 제공자 이름을 알려 준다")
    void providerNameFollowsRoute() {
        LlmClient client = new LlmClient(
                List.of(new RecordingProvider("gemini"), new RecordingProvider("ollama")),
                new LlmRoutingProperties(Map.of(LlmFeature.WEEKLY_SUMMARY, "ollama")));

        assertThat(client.providerName(LlmFeature.WEEKLY_SUMMARY)).isEqualTo("ollama");
        assertThat(client.providerName(LlmFeature.RECOMMENDATION)).isEqualTo("gemini");
    }

    @Test
    @DisplayName("없는 제공자를 고르면 기동할 때 실패한다")
    void unknownProviderFailsFast() {
        assertThatThrownBy(() -> new LlmClient(
                        List.of(new RecordingProvider("gemini")),
                        new LlmRoutingProperties(Map.of(LlmFeature.SEARCH_INTENT, "openai"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openai");
    }

    private static final class RecordingProvider implements LlmProvider {

        private final String name;
        private final List<LlmRequest> requests = new ArrayList<>();

        private RecordingProvider(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String model(LlmFeature feature) {
            return name + "-model";
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            requests.add(request);
            return new LlmResponse("{}", name + "-model", "STOP", 1, 1, 2);
        }
    }
}
