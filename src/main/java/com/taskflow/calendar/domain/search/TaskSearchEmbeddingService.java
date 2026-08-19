package com.taskflow.calendar.domain.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.config.GeminiSearchProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskSearchEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(TaskSearchEmbeddingService.class);

    private final GeminiSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskSearchEmbeddingStore embeddingStore;
    private final TaskRepository taskRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 의미 검색이 왜 안 도는지까지 알려준다.
     * boolean 하나로 합치면 "꺼둔 것"과 "고장난 것"이 구분되지 않는다.
     */
    public SemanticSearchStatus semanticStatus() {
        if (!properties.isSemanticEnabled()
                || properties.getApiKey() == null
                || properties.getApiKey().isBlank()) {
            return SemanticSearchStatus.DISABLED;
        }
        return embeddingStore.isAvailable()
                ? SemanticSearchStatus.READY
                : SemanticSearchStatus.UNAVAILABLE;
    }

    public boolean isSemanticEnabled() {
        return semanticStatus() == SemanticSearchStatus.READY;
    }

    public void ensureEmbeddings(List<Task> tasks) {
        if (!isSemanticEnabled() || tasks.isEmpty()) {
            return;
        }

        Map<Long, String> hashes = embeddingStore.findHashesByTaskIds(
                tasks.stream().map(Task::getId).collect(Collectors.toList())
        );

        List<TaskDocument> staleDocuments = tasks.stream()
                .map(this::toDocument)
                .filter(document -> !document.textHash.equals(hashes.get(document.taskId)))
                .collect(Collectors.toList());

        if (staleDocuments.isEmpty()) {
            return;
        }

        int batchSize = Math.max(1, properties.getEmbeddingBatchSize());
        for (int i = 0; i < staleDocuments.size(); i += batchSize) {
            List<TaskDocument> batch = staleDocuments.subList(i, Math.min(i + batchSize, staleDocuments.size()));
            List<List<Double>> vectors = embedDocuments(batch.stream()
                    .map(document -> document.sourceText)
                    .collect(Collectors.toList()));
            for (int index = 0; index < batch.size() && index < vectors.size(); index++) {
                TaskDocument document = batch.get(index);
                embeddingStore.upsert(document.taskId, document.sourceText, document.textHash, vectors.get(index));
            }
        }
        log.info("Task search embeddings refreshed. refreshedCount={}", staleDocuments.size());
    }

    @Transactional(readOnly = true)
    public void refreshTask(Long taskId) {
        if (!isSemanticEnabled()) {
            return;
        }

        taskRepository.findByIdAndDeletedFalse(taskId)
                .ifPresentOrElse(
                        task -> ensureEmbeddings(List.of(task)),
                        () -> embeddingStore.delete(taskId)
                );
    }

    public void deleteTask(Long taskId) {
        embeddingStore.delete(taskId);
    }

    public Map<Long, Double> searchSimilarities(SearchIntent intent) {
        if (!isSemanticEnabled()) {
            return Map.of();
        }

        List<Double> queryEmbedding = embedQuery(buildSemanticQueryText(intent));
        if (queryEmbedding.isEmpty()) {
            return Map.of();
        }
        return embeddingStore.searchSimilar(queryEmbedding, properties.getSemanticCandidateLimit());
    }

    private List<Double> embedQuery(String text) {
        List<List<Double>> vectors = embedDocuments(List.of(text));
        return vectors.isEmpty() ? List.of() : vectors.get(0);
    }

    private List<List<Double>> embedDocuments(List<String> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }

        String endpoint = properties.getBaseUrl().replaceAll("/$", "")
                + "/models/" + properties.getEmbeddingModel() + ":batchEmbedContents";

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("requests", documents.stream()
                .map(document -> {
                    Map<String, Object> request = new LinkedHashMap<>();
                    request.put("model", "models/" + properties.getEmbeddingModel());
                    request.put("taskType", "RETRIEVAL_DOCUMENT");
                    request.put("content", Map.of("parts", List.of(Map.of("text", document))));
                    return request;
                })
                .collect(Collectors.toList()));

        try {
            String json = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("x-goog-api-key", properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                log.warn("Task search embedding request failed. statusCode={}, body={}", response.statusCode(), abbreviate(response.body()));
                return List.of();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embeddings = root.path("embeddings");
            if (!embeddings.isArray()) {
                return List.of();
            }

            List<List<Double>> vectors = new ArrayList<>();
            for (JsonNode embeddingNode : embeddings) {
                JsonNode valuesNode = embeddingNode.path("values");
                List<Double> vector = new ArrayList<>();
                for (JsonNode valueNode : valuesNode) {
                    vector.add(valueNode.asDouble());
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Task search embedding request failed. message={}", e.getMessage());
            return List.of();
        }
    }

    private TaskDocument toDocument(Task task) {
        String sourceText = normalize(task.getProject().getName())
                + "\n"
                + normalize(task.getTitle())
                + "\n"
                + normalize(task.getDescription());
        return new TaskDocument(task.getId(), sourceText, sha256(sourceText));
    }

    private String buildSemanticQueryText(SearchIntent intent) {
        List<String> parts = new ArrayList<>();
        parts.add(intent.getRawQuery());
        if (!intent.getTopicTerms().isEmpty()) {
            parts.add("topic " + String.join(" ", intent.getTopicTerms()));
        }
        if (intent.getMainAction() != SearchActionIntent.UNKNOWN) {
            parts.add("main_action " + intent.getMainAction().name().toLowerCase(Locale.ROOT));
        }
        if (!intent.getSecondaryActions().isEmpty()) {
            parts.add("secondary_actions " + intent.getSecondaryActions().stream()
                    .map(value -> value.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(" ")));
        }
        if (!intent.getParticipantTerms().isEmpty()) {
            parts.add("participants " + String.join(" ", intent.getParticipantTerms()));
        }
        if (!intent.getLocationTerms().isEmpty()) {
            parts.add("locations " + String.join(" ", intent.getLocationTerms()));
        }
        parts.add("relation_policy " + intent.getRelationPolicy().name().toLowerCase(Locale.ROOT));
        parts.add("domain " + intent.getDomainType().name().toLowerCase(Locale.ROOT));
        parts.add("time " + intent.getTimeIntent().name().toLowerCase(Locale.ROOT));
        return String.join("\n", parts);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not supported", e);
        }
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private static final class TaskDocument {
        private final Long taskId;
        private final String sourceText;
        private final String textHash;

        private TaskDocument(Long taskId, String sourceText, String textHash) {
            this.taskId = taskId;
            this.sourceText = sourceText;
            this.textHash = textHash;
        }
    }
}
