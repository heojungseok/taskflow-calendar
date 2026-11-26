package com.taskflow.assistant.llm;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.HttpOptions;
import com.taskflow.assistant.Gemini429Classifier;
import com.taskflow.assistant.GeminiProperties;
import com.taskflow.common.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import tools.jackson.databind.ObjectMapper;

public class GeminiLlmProvider implements LlmProvider {

    private final Map<LlmFeature, ? extends GeminiProperties> settings;
    private final ObjectMapper objectMapper;
    private final Gemini429Classifier classifier429;
    private final Map<LlmFeature, GoogleGenAiChatModel> models = new ConcurrentHashMap<>();

    public GeminiLlmProvider(Map<LlmFeature, ? extends GeminiProperties> settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.classifier429 = new Gemini429Classifier(objectMapper);
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public String model(LlmFeature feature) {
        return settings.get(feature).getModel();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        GeminiProperties properties = settings.get(request.feature());
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmCallException(
                    ErrorCode.LLM_API_KEY_MISSING,
                    "Gemini API key is not configured",
                    0,
                    0,
                    null,
                    null,
                    null,
                    List.of());
        }
        long startedAt = System.currentTimeMillis();
        try {
            GoogleGenAiChatModel model = models.computeIfAbsent(request.feature(), feature -> createModel(properties));
            return SpringAiChat.call(model, request, options(request));
        } catch (RuntimeException e) {
            throw classify(e, System.currentTimeMillis() - startedAt);
        }
    }

    GoogleGenAiChatOptions options(LlmRequest request) {
        GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder();
        builder.model(settings.get(request.feature()).getModel());
        builder.responseMimeType("application/json");
        builder.outputSchema(objectMapper.writeValueAsString(request.responseSchema()));
        builder.thinkingLevel(GoogleGenAiThinkingLevel.MINIMAL);
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.topK() != null) {
            builder.topK(request.topK());
        }
        if (request.topP() != null) {
            builder.topP(request.topP());
        }
        return builder.build();
    }

    LlmCallException classify(Throwable error, long latencyMs) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiException api) {
                return fromApi(api, latencyMs);
            }
        }
        return new LlmCallException(
                ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE,
                "Gemini request failed: " + error.getMessage(),
                0,
                latencyMs,
                null,
                null,
                null,
                List.of());
    }

    private LlmCallException fromApi(ApiException api, long latencyMs) {
        int code = api.code();
        if (code == 429) {
            return from429(api, latencyMs);
        }
        boolean temporary = code >= 500;
        return new LlmCallException(
                temporary ? ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE : ErrorCode.LLM_CONFIG_INVALID,
                temporary ? "Gemini temporary failure" : "Gemini configuration is invalid",
                code,
                latencyMs,
                null,
                null,
                api.status(),
                List.of());
    }

    private LlmCallException from429(ApiException api, long latencyMs) {
        String body = objectMapper.writeValueAsString(Map.of(
                "error",
                Map.of("code", 429, "status", nullToEmpty(api.status()), "message", nullToEmpty(api.message()))));
        Gemini429Classifier.Classified429 c = classifier429.classify(null, body);
        return new LlmCallException(
                c.errorCode(), c.message(), 429, latencyMs, c.source(), c.retryDelay(), c.upstreamStatus(), c.hints());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static GoogleGenAiChatModel createModel(GeminiProperties properties) {
        Client client = Client.builder()
                .apiKey(properties.getApiKey())
                .httpOptions(HttpOptions.builder()
                        .timeout(properties.getTimeoutSeconds() * 1000)
                        .build())
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .retryTemplate(NoRetry.template())
                .build();
    }
}
