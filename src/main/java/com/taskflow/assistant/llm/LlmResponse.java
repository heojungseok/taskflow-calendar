package com.taskflow.assistant.llm;

public record LlmResponse(
        String text, String model, String finishReason, int promptTokens, int outputTokens, int totalTokens) {}
