package com.taskflow.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.planning.project.Project;
import com.taskflow.planning.project.dto.ProjectResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class TimeZonesTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void UTC로_기록된_시각을_Instant로_바꾼다() {
        assertThat(TimeZones.toUtcInstant(LocalDateTime.of(2026, 10, 10, 10, 13)))
                .isEqualTo(Instant.parse("2026-10-10T10:13:00Z"));
        assertThat(TimeZones.toUtcInstant(null)).isNull();
    }

    @Test
    void 응답_JSON의_기록용_시각에는_UTC_표시가_붙는다() throws Exception {
        Project project = mock(Project.class);
        given(project.getCreatedAt()).willReturn(LocalDateTime.of(2026, 10, 10, 10, 13));

        String json = objectMapper.writeValueAsString(ProjectResponse.from(project));

        assertThat(json).contains("\"createdAt\":\"2026-10-10T10:13:00Z\"");
    }
}
