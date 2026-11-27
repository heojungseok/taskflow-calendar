package com.taskflow.assistant.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskflow.assistant.search.exception.TaskSearchGenerationException;
import com.taskflow.common.ErrorCode;
import com.taskflow.observability.TaskFlowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingResponse;

class TaskSearchEmbeddingResponseTest {

    private final GeminiSearchProperties properties = new GeminiSearchProperties();
    private final TaskSearchEmbeddingService service = new TaskSearchEmbeddingService(
            properties, null, null, null, new TaskFlowMetrics(new SimpleMeterRegistry()), null);

    @BeforeEach
    void setUp() {
        properties.setEmbeddingDimensions(2);
    }

    static Stream<EmbeddingResponse> incompleteResponses() {
        return Stream.of(
                responseOf(),
                responseOf(new float[] {1, 2}),
                responseOf(new float[] {}, new float[] {}),
                responseOf(new float[] {1}, new float[] {2}));
    }

    @ParameterizedTest
    @MethodSource("incompleteResponses")
    @DisplayName("개수나 차원이 맞지 않는 응답은 잘못된 응답이다")
    void rejectsIncompleteEmbeddingResponses(EmbeddingResponse response) {
        assertThatThrownBy(() -> service.toVectors(response, 2))
                .isInstanceOfSatisfying(
                        TaskSearchGenerationException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.LLM_INVALID_RESPONSE));
    }

    @Test
    @DisplayName("벡터 값과 순서를 그대로 지킨다")
    void keepsVectorValuesAndOrder() {
        assertThat(service.toVectors(responseOf(new float[] {1, 2}, new float[] {3, 4}), 2))
                .containsExactly(List.of(1.0, 2.0), List.of(3.0, 4.0));
    }

    private static EmbeddingResponse responseOf(float[]... vectors) {
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            embeddings.add(new Embedding(vectors[i], i));
        }
        return new EmbeddingResponse(embeddings);
    }
}
