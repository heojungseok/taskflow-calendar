package com.taskflow.assistant.llm;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

public class LlmJsonReader {

    // Jackson 3는 JSON 뒤에 토큰이 남으면 기본으로 실패하지만 모델은 JSON 뒤에 텍스트를 붙이기도 한다.
    private final ObjectReader reader;

    public LlmJsonReader(ObjectMapper objectMapper) {
        this.reader = objectMapper.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public JsonNode read(String text) {
        return reader.readTree(text);
    }
}
