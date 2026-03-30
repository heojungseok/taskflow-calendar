package com.taskflow.calendar.domain.search;

import com.taskflow.config.GeminiSearchProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TaskSearchEmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(TaskSearchEmbeddingStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final GeminiSearchProperties properties;
    private final AtomicBoolean available = new AtomicBoolean(true);

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            reconcileEmbeddingTable();
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_task_search_embeddings_updated_at ON task_search_embeddings(updated_at)");
            available.set(true);
        } catch (DataAccessException e) {
            available.set(false);
            log.warn("Task search vector store initialization failed. Semantic search disabled. message={}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available.get();
    }

    public Map<Long, String> findHashesByTaskIds(Collection<Long> taskIds) {
        if (!isAvailable() || taskIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = taskIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> params = new ArrayList<>(taskIds);
        try {
            return jdbcTemplate.query(
                    "SELECT task_id, text_hash FROM task_search_embeddings WHERE task_id IN (" + placeholders + ")",
                    params.toArray(),
                    rs -> {
                        Map<Long, String> hashes = new HashMap<>();
                        while (rs.next()) {
                            hashes.put(rs.getLong("task_id"), rs.getString("text_hash"));
                        }
                        return hashes;
                    }
            );
        } catch (DataAccessException e) {
            available.set(false);
            log.warn("Task search vector hash lookup failed. Semantic search disabled. message={}", e.getMessage());
            return Map.of();
        }
    }

    public void upsert(Long taskId, String sourceText, String textHash, List<Double> embedding) {
        if (!isAvailable()) {
            return;
        }
        if (embedding.size() != properties.getEmbeddingDimensions()) {
            log.warn("Task search vector upsert skipped due to dimension mismatch. taskId={}, expectedDimensions={}, actualDimensions={}",
                    taskId, properties.getEmbeddingDimensions(), embedding.size());
            return;
        }

        try {
            jdbcTemplate.update(
                    "INSERT INTO task_search_embeddings(task_id, source_text, text_hash, embedding, updated_at) " +
                            "VALUES (?, ?, ?, CAST(? AS vector), ?) " +
                            "ON CONFLICT (task_id) DO UPDATE SET " +
                            "source_text = EXCLUDED.source_text, " +
                            "text_hash = EXCLUDED.text_hash, " +
                            "embedding = EXCLUDED.embedding, " +
                            "updated_at = EXCLUDED.updated_at",
                    taskId,
                    sourceText,
                    textHash,
                    toVectorLiteral(embedding),
                    Timestamp.valueOf(LocalDateTime.now())
            );
        } catch (DataAccessException e) {
            available.set(false);
            log.warn("Task search vector upsert failed. Semantic search disabled. taskId={}, message={}", taskId, e.getMessage());
        }
    }

    public void delete(Long taskId) {
        if (!isAvailable()) {
            return;
        }

        try {
            jdbcTemplate.update("DELETE FROM task_search_embeddings WHERE task_id = ?", taskId);
        } catch (DataAccessException e) {
            available.set(false);
            log.warn("Task search vector delete failed. Semantic search disabled. taskId={}, message={}", taskId, e.getMessage());
        }
    }

    public Map<Long, Double> searchSimilar(List<Double> queryEmbedding, int limit) {
        if (!isAvailable() || queryEmbedding.isEmpty()) {
            return Map.of();
        }
        if (queryEmbedding.size() != properties.getEmbeddingDimensions()) {
            log.warn("Task search vector query skipped due to dimension mismatch. expectedDimensions={}, actualDimensions={}",
                    properties.getEmbeddingDimensions(), queryEmbedding.size());
            return Map.of();
        }

        try {
            List<SemanticMatch> matches = jdbcTemplate.query(
                    "SELECT task_id, GREATEST(0, 1 - (embedding <=> CAST(? AS vector))) AS similarity " +
                            "FROM task_search_embeddings " +
                            "ORDER BY embedding <=> CAST(? AS vector) " +
                            "LIMIT ?",
                    new Object[]{toVectorLiteral(queryEmbedding), toVectorLiteral(queryEmbedding), limit},
                    (rs, rowNum) -> new SemanticMatch(rs.getLong("task_id"), rs.getDouble("similarity"))
            );
            Map<Long, Double> result = new HashMap<>();
            for (SemanticMatch match : matches) {
                result.put(match.taskId, match.similarity);
            }
            return result;
        } catch (DataAccessException e) {
            available.set(false);
            log.warn("Task search vector query failed. Semantic search disabled. message={}", e.getMessage());
            return Map.of();
        }
    }

    private String toVectorLiteral(List<Double> embedding) {
        return "[" + embedding.stream()
                .map(value -> String.format(Locale.US, "%.8f", value))
                .collect(Collectors.joining(",")) + "]";
    }

    private void reconcileEmbeddingTable() {
        Integer currentDimensions = currentEmbeddingDimensions();
        if (currentDimensions != null && currentDimensions != properties.getEmbeddingDimensions()) {
            log.warn("Task search embedding dimension changed. Recreating table. currentDimensions={}, targetDimensions={}",
                    currentDimensions, properties.getEmbeddingDimensions());
            jdbcTemplate.execute("DROP TABLE IF EXISTS task_search_embeddings");
        }
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS task_search_embeddings (" +
                        "task_id BIGINT PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE," +
                        "source_text TEXT NOT NULL," +
                        "text_hash VARCHAR(64) NOT NULL," +
                        "embedding vector(" + properties.getEmbeddingDimensions() + ") NOT NULL," +
                        "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ")"
        );
    }

    private Integer currentEmbeddingDimensions() {
        try {
            return jdbcTemplate.query(
                    "SELECT a.atttypmod - 4 AS dimensions " +
                            "FROM pg_attribute a " +
                            "JOIN pg_class c ON a.attrelid = c.oid " +
                            "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                            "WHERE c.relname = 'task_search_embeddings' " +
                            "AND a.attname = 'embedding' " +
                            "AND a.attnum > 0 " +
                            "AND NOT a.attisdropped " +
                            "LIMIT 1",
                    rs -> rs.next() ? rs.getInt("dimensions") : null
            );
        } catch (DataAccessException e) {
            log.warn("Task search embedding dimension lookup failed. message={}", e.getMessage());
            return null;
        }
    }

    private static final class SemanticMatch {
        private final Long taskId;
        private final double similarity;

        private SemanticMatch(Long taskId, double similarity) {
            this.taskId = taskId;
            this.similarity = similarity;
        }
    }
}
