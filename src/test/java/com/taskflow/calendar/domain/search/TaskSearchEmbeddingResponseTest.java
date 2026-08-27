package com.taskflow.calendar.domain.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.search.exception.TaskSearchGenerationException;
import com.taskflow.common.ErrorCode;
import com.taskflow.config.GeminiSearchProperties;
import com.taskflow.observability.TaskFlowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskSearchEmbeddingResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsIncompleteEmbeddingResponses() throws Exception {
        GeminiSearchProperties properties = new GeminiSearchProperties();
        properties.setEmbeddingDimensions(2);
        TaskSearchEmbeddingService service = new TaskSearchEmbeddingService(
                properties, objectMapper, null, null, null,
                new TaskFlowMetrics(new SimpleMeterRegistry()));

        for (String json : List.of(
                "{\"embeddings\":[]}",
                "{\"embeddings\":[{\"values\":[1,2]}]}",
                "{\"embeddings\":[{},{}]}",
                "{\"embeddings\":[{\"values\":[1]},{\"values\":[2]}]}",
                "{\"embeddings\":[{\"values\":[1,\"x\"]},{\"values\":[2,3]}]}")) {
            JsonNode response = objectMapper.readTree(json);
            assertThatThrownBy(() -> service.parseEmbeddings(response, 2))
                    .isInstanceOfSatisfying(TaskSearchGenerationException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.LLM_INVALID_RESPONSE));
        }

        assertThat(service.parseEmbeddings(objectMapper.readTree(
                "{\"embeddings\":[{\"values\":[1,2]},{\"values\":[3,4]}]}"), 2))
                .containsExactly(List.of(1.0, 2.0), List.of(3.0, 4.0));
    }
}
