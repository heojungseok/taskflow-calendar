package com.taskflow.assistant.llm.bakeoff;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

record BakeoffConfig(
        List<String> ollamaModels,
        String ollamaBaseUrl,
        String geminiApiKey,
        String geminiModel,
        Set<String> features,
        int repeat,
        int concurrency) {

    static final String DEFAULT_OLLAMA_MODELS = "hf.co/unsloth/gemma-4-E2B-it-GGUF:Q4_K_M,"
            + "hf.co/unsloth/Qwen3.5-4B-GGUF:Q4_K_M,hf.co/unsloth/Phi-4-mini-instruct-GGUF:Q4_K_M";
    static final String DEFAULT_FEATURES = "search,recommendation,summary";

    static BakeoffConfig fromEnv() {
        return new BakeoffConfig(
                csv(env("LLM_BAKEOFF_OLLAMA_MODELS", DEFAULT_OLLAMA_MODELS)),
                env("LLM_BAKEOFF_OLLAMA_BASE_URL", "http://localhost:11434"),
                env("LLM_BAKEOFF_GEMINI_API_KEY", ""),
                env("LLM_BAKEOFF_GEMINI_MODEL", "gemini-3.5-flash-lite"),
                Set.copyOf(csv(env("LLM_BAKEOFF_FEATURES", DEFAULT_FEATURES))),
                Math.max(1, Integer.parseInt(env("LLM_BAKEOFF_REPEAT", "1"))),
                Math.max(0, Integer.parseInt(env("LLM_BAKEOFF_CONCURRENCY", "3"))));
    }

    boolean geminiEnabled() {
        return !geminiApiKey.isBlank();
    }

    boolean runs(String feature) {
        return features.contains(feature);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static List<String> csv(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return parts;
    }
}
