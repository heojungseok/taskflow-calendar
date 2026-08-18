package com.taskflow.calendar.domain.search;

import com.taskflow.config.GeminiSearchProperties;
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
 * 스키마는 건드리지 않고 읽기만 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskSearchEmbeddingDimensionTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

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
}
