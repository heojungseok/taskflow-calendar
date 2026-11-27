package com.taskflow.assistant.search.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskflow.assistant.llm.LlmFeature;
import com.taskflow.assistant.llm.LlmRequest;
import com.taskflow.assistant.search.GeminiSearchProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchIntentRequestTest {

    @Test
    @DisplayName("검색 의도 요청은 기능, 온도, 시스템 지시, 질의가 담긴 프롬프트, 스키마를 담는다")
    void buildsSearchIntentRequest() {
        GeminiSearchProperties properties = new GeminiSearchProperties();

        LlmRequest request = SearchIntentRequest.build(properties, "이번 주 배포");

        assertThat(request.feature()).isEqualTo(LlmFeature.SEARCH_INTENT);
        assertThat(request.temperature()).isEqualTo(properties.getTemperature());
        assertThat(request.topK()).isNull();
        assertThat(request.topP()).isNull();
        assertThat(request.systemInstruction()).startsWith("당신은 자연어 Task 검색 질의를 구조화하는 intent parsing 보조자다.");
        assertThat(request.userPrompt()).startsWith("아래 자연어 검색 질의를 Task 검색 intent로 구조화하라.");
        assertThat(request.userPrompt()).endsWith("query: 이번 주 배포");
        assertThat(request.responseSchema()).containsKeys("properties", "required");
    }
}
