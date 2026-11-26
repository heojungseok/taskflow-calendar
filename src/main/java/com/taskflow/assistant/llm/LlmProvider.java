package com.taskflow.assistant.llm;

public interface LlmProvider {

    String name();

    LlmResponse generate(LlmRequest request);
}
