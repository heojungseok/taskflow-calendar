package com.taskflow.assistant.llm;

import com.taskflow.common.ErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

public class OllamaLlmProvider implements LlmProvider {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final OllamaLlmProperties properties;
    private final ObjectMapper objectMapper;
    private volatile OllamaChatModel chatModel;

    public OllamaLlmProvider(OllamaLlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        long startedAt = System.currentTimeMillis();
        try {
            return SpringAiChat.call(model(), request, options(request));
        } catch (RuntimeException e) {
            throw classify(e, System.currentTimeMillis() - startedAt);
        }
    }

    OllamaChatOptions options(LlmRequest request) {
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder();
        builder.model(properties.getModel());
        builder.outputSchema(objectMapper.writeValueAsString(request.responseSchema()));
        builder.disableThinking();
        builder.keepAlive(properties.getKeepAlive());
        builder.numCtx(properties.getNumCtx());
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
            if (cause instanceof RestClientResponseException response) {
                return fromStatus(response.getStatusCode().value(), latencyMs);
            }
        }
        return new LlmCallException(
                ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE,
                "Ollama request failed: " + error.getMessage(),
                0,
                latencyMs,
                null,
                null,
                null,
                List.of());
    }

    private LlmCallException fromStatus(int status, long latencyMs) {
        if (status >= 500) {
            return new LlmCallException(
                    ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE,
                    "Ollama temporary failure",
                    status,
                    latencyMs,
                    null,
                    null,
                    null,
                    List.of());
        }
        return new LlmCallException(
                ErrorCode.LLM_CONFIG_INVALID,
                "Ollama configuration is invalid",
                status,
                latencyMs,
                null,
                null,
                null,
                List.of());
    }

    private OllamaChatModel model() {
        OllamaChatModel current = chatModel;
        if (current == null) {
            synchronized (this) {
                if (chatModel == null) {
                    chatModel = createModel();
                }
                current = chatModel;
            }
        }
        return current;
    }

    private OllamaChatModel createModel() {
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
        OllamaApi api = OllamaApi.builder()
                .baseUrl(properties.getBaseUrl())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .retryTemplate(NoRetry.template())
                .build();
    }
}
