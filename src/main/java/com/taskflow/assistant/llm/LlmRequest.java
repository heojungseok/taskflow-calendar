package com.taskflow.assistant.llm;

import java.util.Map;

public record LlmRequest(
        LlmFeature feature,
        String systemInstruction,
        String userPrompt,
        Map<String, Object> responseSchema,
        Double temperature,
        Integer topK,
        Double topP) {}
