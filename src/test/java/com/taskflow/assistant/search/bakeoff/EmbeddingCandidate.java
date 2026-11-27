package com.taskflow.assistant.search.bakeoff;

import java.util.ArrayList;
import java.util.List;

record EmbeddingCandidate(String name, String base, int dimensions, String queryPrefix, String documentPrefix) {

    static final String GEMINI = "gemini";

    static final List<String> DEFAULT_NAMES = List.of("bge-m3", "qwen3-embedding-0.6b", "embeddinggemma");

    static final List<EmbeddingCandidate> ALL = List.of(
            new EmbeddingCandidate("gemini-3072", GEMINI, 0, "", ""),
            new EmbeddingCandidate("gemini-1536", GEMINI, 1536, "", ""),
            new EmbeddingCandidate("gemini-768", GEMINI, 768, "", ""),
            new EmbeddingCandidate("bge-m3", "ollama:bge-m3", 0, "", ""),
            new EmbeddingCandidate(
                    "qwen3-embedding-0.6b",
                    "ollama:qwen3-embedding:0.6b",
                    0,
                    "Instruct: Given a personal task search query, retrieve the matching tasks\nQuery: ",
                    ""),
            new EmbeddingCandidate(
                    "embeddinggemma",
                    "ollama:embeddinggemma",
                    0,
                    "task: search result | query: ",
                    "title: none | text: "));

    boolean gemini() {
        return GEMINI.equals(base);
    }

    String ollamaModel() {
        return base.substring("ollama:".length());
    }

    String cacheKey() {
        return base + "|" + queryPrefix + "|" + documentPrefix;
    }

    static List<EmbeddingCandidate> select(String names) {
        List<String> requested = names == null || names.isBlank() ? DEFAULT_NAMES : List.of(names.split(","));
        List<EmbeddingCandidate> selected = new ArrayList<>();
        for (String name : requested) {
            ALL.stream()
                    .filter(candidate -> candidate.name().equals(name.trim()))
                    .findFirst()
                    .ifPresentOrElse(selected::add, () -> {
                        throw new IllegalArgumentException("unknown candidate " + name);
                    });
        }
        return selected;
    }
}
