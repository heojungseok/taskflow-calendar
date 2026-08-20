package com.taskflow.calendar.domain.search;

import com.taskflow.config.GeminiSearchProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
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
    // ponytail: 한 번 false가 되면 재기동 전까지 돌아오지 않는다. 커넥션이 한 번 끊겨도
    // 그 뒤로는 계속 UNAVAILABLE이다. 예전에는 로그에만 남아 티가 안 났지만 이제 화면에 뜬다.
    // 오탐이 잦아지면 주기적 재프로브(또는 실패 후 N분 뒤 1회 재시도)를 붙인다.
    private final AtomicBoolean available = new AtomicBoolean(true);

    @PostConstruct
    public void initialize() {
        try {
            if (properties.isSchemaManagementEnabled()) {
                jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            }

            Integer currentDimensions = currentEmbeddingDimensions();
            int targetDimensions = properties.getEmbeddingDimensions();
            if (currentDimensions == null && !properties.isSchemaManagementEnabled()) {
                available.set(false);
                log.error("Task search embedding schema missing. Run the deployment migration first.");
                return;
            }
            if (currentDimensions != null && currentDimensions != targetDimensions) {
                // 예전에는 여기서 테이블을 DROP 했다. atttypmod를 4 적게 읽는 버그와 만나
                // 매 기동마다 임베딩이 통째로 지워졌다. 읽기 실수가 데이터 소실이 되면 안 된다.
                // 기존 테이블은 그대로 두고 비활성으로 떨어뜨린다. 이전은 사람이 판단한다.
                available.set(false);
                log.error("Task search embedding dimension mismatch. Semantic search unavailable. "
                                + "Existing table task_search_embeddings kept — migrate manually "
                                + "(dump, recreate with the new dimension, re-embed). "
                                + "currentDimensions={}, targetDimensions={}",
                        currentDimensions, targetDimensions);
                return;
            }

            if (properties.isSchemaManagementEnabled()) {
                createEmbeddingTable();
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_task_search_embeddings_updated_at ON task_search_embeddings(updated_at)");
            }
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

    private void createEmbeddingTable() {
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

    /**
     * pgvector의 vector(N)은 atttypmod에 N을 그대로 담는다. varchar처럼 -4 하면 안 된다.
     * 빼면 매 기동마다 3072를 3068로 읽어 "차원이 바뀌었다"고 판단하고 테이블을 드롭한다.
     */
    Integer currentEmbeddingDimensions() {
        try {
            return jdbcTemplate.query(
                    "SELECT a.atttypmod AS dimensions " +
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
