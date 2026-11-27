package com.taskflow.assistant.search.bakeoff;

import com.taskflow.assistant.llm.GeminiEmbeddingModel;
import com.taskflow.assistant.search.GeminiSearchProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 후보별 임베딩을 만들고 build 디렉터리에 캐시해 다시 돌릴 때 API를 부르지 않는다. */
final class EmbeddingVectors {

    private static final int BATCH = 16;

    private final ObjectMapper objectMapper;
    private final Path cacheDir;
    private final String ollamaBaseUrl;
    private final String geminiApiKey;

    EmbeddingVectors(ObjectMapper objectMapper, Path cacheDir, String ollamaBaseUrl, String geminiApiKey) {
        this.objectMapper = objectMapper;
        this.cacheDir = cacheDir;
        this.ollamaBaseUrl = ollamaBaseUrl.replaceAll("/+$", "");
        this.geminiApiKey = geminiApiKey;
    }

    record Timed(Map<String, float[]> vectors, List<Long> queryMillis, long documentMillis) {}

    Timed embed(EmbeddingCandidate candidate, List<String> documents, List<String> queries) {
        Map<String, float[]> cache = readCache(candidate);
        EmbeddingModel model = null;
        long documentMillis = 0;
        List<String> missingDocuments = new ArrayList<>();
        for (String document : documents) {
            if (!cache.containsKey(candidate.documentPrefix() + document)) {
                missingDocuments.add(candidate.documentPrefix() + document);
            }
        }
        if (!missingDocuments.isEmpty()) {
            model = model(candidate);
            long started = System.nanoTime();
            for (int from = 0; from < missingDocuments.size(); from += BATCH) {
                List<String> batch = missingDocuments.subList(from, Math.min(from + BATCH, missingDocuments.size()));
                put(cache, batch, model.call(new EmbeddingRequest(batch, null)));
            }
            documentMillis = (System.nanoTime() - started) / 1_000_000;
        }
        List<Long> queryMillis = new ArrayList<>();
        for (String query : queries) {
            String text = candidate.queryPrefix() + query;
            if (cache.containsKey(text)) {
                continue;
            }
            model = model == null ? model(candidate) : model;
            long started = System.nanoTime();
            put(cache, List.of(text), model.call(new EmbeddingRequest(List.of(text), null)));
            queryMillis.add((System.nanoTime() - started) / 1_000_000);
        }
        writeCache(candidate, cache);
        return new Timed(cache, queryMillis, documentMillis);
    }

    void unload(EmbeddingCandidate candidate) {
        if (candidate.gemini()) {
            return;
        }
        String body = "{\"model\":\"" + candidate.ollamaModel() + "\",\"input\":[],\"keep_alive\":0}";
        try {
            HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/embed"))
                                    .timeout(Duration.ofSeconds(10))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            System.out.println("[embedding-bakeoff] unload 실패 " + candidate.name() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private EmbeddingModel model(EmbeddingCandidate candidate) {
        if (candidate.gemini()) {
            if (geminiApiKey.isBlank()) {
                throw new IllegalStateException("EMBEDDING_BAKEOFF_GEMINI_API_KEY is required for " + candidate.name());
            }
            GeminiSearchProperties properties = new GeminiSearchProperties();
            properties.setApiKey(geminiApiKey);
            return new GeminiEmbeddingModel(properties, properties.getEmbeddingModel(), objectMapper);
        }
        return OllamaEmbeddingModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(ollamaBaseUrl).build())
                .options(OllamaEmbeddingOptions.builder()
                        .model(candidate.ollamaModel())
                        .keepAlive("10m")
                        .build())
                .build();
    }

    private static void put(Map<String, float[]> cache, List<String> texts, EmbeddingResponse response) {
        if (response.getResults().size() != texts.size()) {
            throw new IllegalStateException(
                    "embedding count mismatch " + response.getResults().size());
        }
        for (int i = 0; i < texts.size(); i++) {
            cache.put(texts.get(i), response.getResults().get(i).getOutput());
        }
    }

    private Path cacheFile(EmbeddingCandidate candidate) {
        return cacheDir.resolve(Integer.toHexString(candidate.cacheKey().hashCode()) + ".json");
    }

    private Map<String, float[]> readCache(EmbeddingCandidate candidate) {
        Path file = cacheFile(candidate);
        if (!Files.exists(file)) {
            return new HashMap<>();
        }
        return new HashMap<>(objectMapper.readValue(file.toFile(), new TypeReference<Map<String, float[]>>() {}));
    }

    private void writeCache(EmbeddingCandidate candidate, Map<String, float[]> cache) {
        try {
            Files.createDirectories(cacheDir);
            objectMapper.writeValue(cacheFile(candidate).toFile(), cache);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
