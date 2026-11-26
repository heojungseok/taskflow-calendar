package com.taskflow.assistant.llm;

public interface LlmProvider {

    String name();

    String model(LlmFeature feature);

    LlmResponse generate(LlmRequest request);
}
