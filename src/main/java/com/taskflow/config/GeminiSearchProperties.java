package com.taskflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gemini.search")
public class GeminiSearchProperties extends GeminiProperties {

    private String embeddingModel = "gemini-embedding-001";
    private boolean semanticEnabled = true;
    private int embeddingDimensions = 3072;
    private int lexicalCandidateLimit = 50;
    private int semanticCandidateLimit = 50;
    private int embeddingBatchSize = 16;

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public boolean isSemanticEnabled() {
        return semanticEnabled;
    }

    public void setSemanticEnabled(boolean semanticEnabled) {
        this.semanticEnabled = semanticEnabled;
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public void setEmbeddingDimensions(int embeddingDimensions) {
        this.embeddingDimensions = embeddingDimensions;
    }

    public int getLexicalCandidateLimit() {
        return lexicalCandidateLimit;
    }

    public void setLexicalCandidateLimit(int lexicalCandidateLimit) {
        this.lexicalCandidateLimit = lexicalCandidateLimit;
    }

    public int getSemanticCandidateLimit() {
        return semanticCandidateLimit;
    }

    public void setSemanticCandidateLimit(int semanticCandidateLimit) {
        this.semanticCandidateLimit = semanticCandidateLimit;
    }

    public int getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }

    public void setEmbeddingBatchSize(int embeddingBatchSize) {
        this.embeddingBatchSize = embeddingBatchSize;
    }
}
