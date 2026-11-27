package com.taskflow.assistant.llm;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import com.taskflow.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.util.function.SingletonSupplier;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring AI 2.1 GoogleGenAiTextEmbeddingModel이 taskType을 SDK 요청에 넣지 않아,
 * 저장된 벡터와 맞추려고 SDK를 직접 부른다.
 */
public class GeminiEmbeddingModel implements EmbeddingModel {

    private static final String TASK_TYPE = "RETRIEVAL_DOCUMENT";

    private final GeminiProperties properties;
    private final String model;
    private final GeminiErrorClassifier errorClassifier;
    private final SingletonSupplier<Client> client;

    public GeminiEmbeddingModel(GeminiProperties properties, String model, ObjectMapper objectMapper) {
        this.properties = properties;
        this.model = model;
        this.errorClassifier = new GeminiErrorClassifier(objectMapper);
        this.client = SingletonSupplier.of(() -> GeminiGenAiClient.create(properties));
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw LlmCallException.of(ErrorCode.LLM_API_KEY_MISSING, "Gemini API key is not configured", 0, 0);
        }
        long startedAt = System.currentTimeMillis();
        EmbedContentResponse response;
        try {
            EmbedContentConfig config =
                    EmbedContentConfig.builder().taskType(TASK_TYPE).build();
            response = client.obtain().models.embedContent(model, request.getInstructions(), config);
        } catch (RuntimeException e) {
            throw errorClassifier.classify(e, System.currentTimeMillis() - startedAt);
        }
        return toResponse(response, System.currentTimeMillis() - startedAt);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getFormattedContent());
    }

    private static EmbeddingResponse toResponse(EmbedContentResponse response, long latencyMs) {
        List<ContentEmbedding> embeddings = response.embeddings().orElse(List.of());
        List<Embedding> results = new ArrayList<>(embeddings.size());
        for (int index = 0; index < embeddings.size(); index++) {
            results.add(new Embedding(toArray(embeddings.get(index), latencyMs), index));
        }
        return new EmbeddingResponse(results);
    }

    private static float[] toArray(ContentEmbedding embedding, long latencyMs) {
        List<Float> values = embedding.values().orElse(List.of());
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) {
            Float value = values.get(i);
            if (value == null) {
                throw LlmCallException.of(
                        ErrorCode.LLM_INVALID_RESPONSE, "Gemini embedding contained a null value", 200, latencyMs);
            }
            vector[i] = value;
        }
        return vector;
    }
}
