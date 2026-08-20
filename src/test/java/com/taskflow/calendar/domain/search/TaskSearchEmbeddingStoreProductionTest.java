package com.taskflow.calendar.domain.search;

import com.taskflow.config.GeminiSearchProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskSearchEmbeddingStoreProductionTest {

    @Test
    void productionValidationDoesNotExecuteDdl() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        GeminiSearchProperties properties = new GeminiSearchProperties();
        properties.setSchemaManagementEnabled(false);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class)))
                .thenReturn(properties.getEmbeddingDimensions());

        new TaskSearchEmbeddingStore(jdbcTemplate, properties).initialize();

        verify(jdbcTemplate, never()).execute(anyString());
    }
}
