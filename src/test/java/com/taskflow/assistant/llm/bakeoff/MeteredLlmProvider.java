package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.assistant.llm.LlmFeature;
import com.taskflow.assistant.llm.LlmProvider;
import com.taskflow.assistant.llm.LlmRequest;
import com.taskflow.assistant.llm.LlmResponse;

final class MeteredLlmProvider implements LlmProvider {

    private static final ThreadLocal<LlmResponse> LAST_RESPONSE = new ThreadLocal<>();

    private final LlmProvider delegate;

    MeteredLlmProvider(LlmProvider delegate) {
        this.delegate = delegate;
    }

    static void reset() {
        LAST_RESPONSE.remove();
    }

    static Integer lastOutputTokens() {
        LlmResponse response = LAST_RESPONSE.get();
        return response == null ? null : response.outputTokens();
    }

    static Integer lastPromptTokens() {
        LlmResponse response = LAST_RESPONSE.get();
        return response == null ? null : response.promptTokens();
    }

    static String lastText() {
        LlmResponse response = LAST_RESPONSE.get();
        return response == null ? null : response.text();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String model(LlmFeature feature) {
        return delegate.model(feature);
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        LlmResponse response = delegate.generate(request);
        LAST_RESPONSE.set(response);
        return response;
    }
}
