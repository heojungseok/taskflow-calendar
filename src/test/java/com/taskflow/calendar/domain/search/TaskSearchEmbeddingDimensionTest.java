package com.taskflow.calendar.domain.search;

import com.taskflow.config.GeminiSearchProperties;

import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * atttypmod에서 4를 빼면(varchar 관례) vector(3072)를 3068로 읽는다.
 * 그러면 매 기동마다 "차원이 바뀌었다"고 판단해 임베딩 테이블을 드롭한다.
 *
 * pgvector의 typmod 동작이라 H2로는 검증되지 않는다 - 실제 taskflow-postgres에 붙는다.
 * @BeforeEach가 테이블을 보장하려 CREATE를 태우지만 @DataJpaTest가 롤백한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskSearchEmbeddingDimensionTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    /**
     * 테이블이 없는 DB(CI 서비스 컨테이너 등)에서도 전제가 서도록 정상 차원으로 한 번 만든다.
     * 이미 있으면 CREATE TABLE IF NOT EXISTS라 아무 일도 일어나지 않는다.
     */
    @BeforeEach
    void ensureEmbeddingTableExists() {
        GeminiSearchProperties properties = new GeminiSearchProperties();
        properties.setSchemaManagementEnabled(true);
        new TaskSearchEmbeddingStore(jdbcTemplate, properties).initialize();
    }

    /** 선언된 타입 문자열("vector(3072)")에서 차원을 뽑는다. 조회 코드와 독립적인 경로다. */
    private int declaredDimensions() {
        String type = jdbcTemplate.queryForObject(
                "SELECT format_type(a.atttypid, a.atttypmod) "
                        + "FROM pg_attribute a "
                        + "JOIN pg_class c ON a.attrelid = c.oid "
                        + "WHERE c.relname = 'task_search_embeddings' "
                        + "AND a.attname = 'embedding' AND NOT a.attisdropped",
                String.class);

        assertThat(type).matches("vector\\(\\d+\\)");
        return Integer.parseInt(type.replaceAll("\\D+", ""));
    }

    @Test
    @DisplayName("조회한 차원이 실제 선언과 일치한다 - 어긋나면 매 기동마다 테이블이 드롭된다")
    void lookupMatchesDeclaredDimensions() {
        int declared = declaredDimensions();

        GeminiSearchProperties properties = new GeminiSearchProperties();
        properties.setEmbeddingDimensions(declared);
        TaskSearchEmbeddingStore store = new TaskSearchEmbeddingStore(jdbcTemplate, properties);

        assertThat(store.currentEmbeddingDimensions()).isEqualTo(declared);
    }

    @Test
    @DisplayName("차원이 어긋나도 임베딩 행을 지우지 않는다 - 읽기 실수가 데이터 소실이 되면 안 된다")
    void dimensionMismatchKeepsExistingRows() {
        int declared = declaredDimensions();
        insertProbeRow(declared);
        long before = rowCount();

        GeminiSearchProperties properties = new GeminiSearchProperties();
        properties.setEmbeddingDimensions(declared + 1);
        TaskSearchEmbeddingStore store = new TaskSearchEmbeddingStore(jdbcTemplate, properties);

        store.initialize();

        assertThat(store.isAvailable()).isFalse();
        // 테이블 타입이 아니라 행이 살아있는지를 본다. DROP을 DELETE로 바꿔도 걸리게.
        assertThat(rowCount()).isEqualTo(before);
        assertThat(declaredDimensions()).isEqualTo(declared);
    }

    private long rowCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM task_search_embeddings", Long.class);
    }

    /** 살아남는지 볼 대상이 필요하다. 기존 행이 0건일 수 있어 직접 하나 넣는다. */
    private void insertProbeRow(int dimensions) {
        Long projectId = jdbcTemplate.queryForObject(
                "INSERT INTO projects(name, created_at, updated_at) "
                        + "VALUES ('dimension probe', now(), now()) RETURNING id",
                Long.class);
        Long taskId = jdbcTemplate.queryForObject(
                "INSERT INTO tasks(project_id, title, status, deleted, calendar_sync_enabled, created_at, updated_at) "
                        + "VALUES (?, 'dimension probe', 'REQUESTED', false, false, now(), now()) RETURNING id",
                Long.class, projectId);
        String vector = "[" + String.join(",", Collections.nCopies(dimensions, "0.1")) + "]";
        jdbcTemplate.update(
                "INSERT INTO task_search_embeddings(task_id, source_text, text_hash, embedding, updated_at) "
                        + "VALUES (?, 'probe', 'probe-hash', CAST(? AS vector), now())",
                taskId, vector);
    }
}
