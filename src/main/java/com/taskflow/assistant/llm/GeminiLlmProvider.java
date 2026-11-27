package com.taskflow.assistant.llm;

import com.taskflow.common.ErrorCode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import tools.jackson.databind.ObjectMapper;

public class GeminiLlmProvider implements LlmProvider {

    private final Map<LlmFeature, ? extends GeminiProperties> settings;
    private final ObjectMapper objectMapper;
    private final GeminiErrorClassifier errorClassifier;
    private final Map<LlmFeature, GoogleGenAiChatModel> models = new ConcurrentHashMap<>();

    public GeminiLlmProvider(Map<LlmFeature, ? extends GeminiProperties> settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.errorClassifier = new GeminiErrorClassifier(objectMapper);
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
            throw LlmCallException.of(ErrorCode.LLM_API_KEY_MISSING, "Gemini API key is not configured", 0, 0);
        }
        long startedAt = System.currentTimeMillis();
        try {
            GoogleGenAiChatModel model = models.computeIfAbsent(request.feature(), feature -> createModel(properties));
            return SpringAiChat.call(model, request, options(request));
        } catch (RuntimeException e) {
            throw errorClassifier.classify(e, System.currentTimeMillis() - startedAt);
        }
    }

    GoogleGenAiChatOptions options(LlmRequest request) {
        GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder();
        builder.model(settings.get(request.feature()).getModel());
        builder.responseMimeType("application/json");
        builder.outputSchema(objectMapper.writeValueAsString(request.responseSchema()));
        // Gemini 3 계열은 thinkingBudget을 거부해 thinkingLevel로만 생각을 줄일 수 있다.
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

    private static GoogleGenAiChatModel createModel(GeminiProperties properties) {
        return GoogleGenAiChatModel.builder()
                .genAiClient(GeminiGenAiClient.create(properties))
                .retryTemplate(NoRetry.template())
                .build();
    }
}
