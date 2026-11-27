package com.taskflow.assistant.llm;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.time.Duration;
import java.util.List;
import okhttp3.OkHttpClient;

final class GeminiGenAiClient {

    private GeminiGenAiClient() {}

    static Client create(GeminiProperties properties) {
        Duration timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .callTimeout(timeout)
                .retryOnConnectionFailure(false)
                .addInterceptor(new GeminiHttpErrorInterceptor())
                .build();
        String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
        int versionStart = baseUrl.lastIndexOf('/');
        return Client.builder()
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
    }
}
