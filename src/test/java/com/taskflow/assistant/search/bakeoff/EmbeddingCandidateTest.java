package com.taskflow.assistant.search.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmbeddingCandidateTest {

    @Test
    @DisplayName("후보를 고르지 않으면 로컬 모델만 비교한다")
    void defaultsToLocalModels() {
        assertThat(EmbeddingCandidate.select(null))
                .extracting(EmbeddingCandidate::name)
                .containsExactly("bge-m3", "qwen3-embedding-0.6b", "embeddinggemma");
        assertThat(EmbeddingCandidate.select("")).noneMatch(EmbeddingCandidate::gemini);
    }

    @Test
    @DisplayName("Gemini 후보는 이름으로 고를 때만 들어간다")
    void geminiOnlyWhenNamed() {
        assertThat(EmbeddingCandidate.select("gemini-3072, bge-m3"))
                .extracting(EmbeddingCandidate::name)
                .containsExactly("gemini-3072", "bge-m3");
    }
}
