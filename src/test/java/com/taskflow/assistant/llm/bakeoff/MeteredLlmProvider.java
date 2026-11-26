package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.assistant.llm.LlmFeature;
import com.taskflow.assistant.llm.LlmProvider;
import com.taskflow.assistant.llm.LlmRequest;
import com.taskflow.assistant.llm.LlmResponse;

final class MeteredLlmProvider implements LlmProvider {

    private static final ThreadLocal<Integer> LAST_OUTPUT_TOKENS = new ThreadLocal<>();

    private final LlmProvider delegate;

    MeteredLlmProvider(LlmProvider delegate) {
        this.delegate = delegate;
    }

    static void reset() {
        LAST_OUTPUT_TOKENS.remove();
    }

    static Integer lastOutputTokens() {
        return LAST_OUTPUT_TOKENS.get();
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
        LAST_OUTPUT_TOKENS.set(response.outputTokens());
        return response;
    }
}
