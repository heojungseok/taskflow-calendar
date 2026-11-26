package com.taskflow.assistant.llm;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.taskflow.common.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.OkHttpClient;
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
            throw classify(e, System.currentTimeMillis() - startedAt);
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

    LlmCallException classify(Throwable error, long latencyMs) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof GeminiHttpException http) {
                return errorClassifier.classify(http.statusCode(), http.retryAfter(), http.body(), latencyMs);
            }
            if (cause instanceof IllegalArgumentException) {
                return LlmCallException.of(
                        ErrorCode.LLM_CONFIG_INVALID, "Gemini request is invalid: " + cause.getMessage(), 0, latencyMs);
            }
        }
        return LlmCallException.of(
                ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE, "Gemini request failed: " + error.getMessage(), 0, latencyMs);
    }

    private static GoogleGenAiChatModel createModel(GeminiProperties properties) {
        Duration timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .callTimeout(timeout)
                .addInterceptor(new GeminiHttpErrorInterceptor())
                .build();
        String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
        int versionStart = baseUrl.lastIndexOf('/');
        Client client = Client.builder()
                .apiKey(properties.getApiKey())
                .clientOptions(
                        ClientOptions.builder().customHttpClient(httpClient).build())
                .httpOptions(HttpOptions.builder()
                        .baseUrl(baseUrl.substring(0, versionStart))
                        .apiVersion(baseUrl.substring(versionStart + 1))
                        .timeout((int) timeout.toMillis())
                        .retryOptions(HttpRetryOptions.builder()
                                .attempts(1)
                                .httpStatusCodes(List.of())
                                .build())
                        .build())
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .retryTemplate(NoRetry.template())
                .build();
    }
}
