package com.taskflow.assistant.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LlmJsonReaderTest {

    private final LlmJsonReader reader = new LlmJsonReader(JsonMapper.builder().build());

    @ParameterizedTest
    @ValueSource(
            strings = {"{\"summary\":\"요약\"}", "{\"summary\":\"요약\"} {\"summary\":\"반복\"}", "{\"summary\":\"요약\"}\n```"
            })
    @DisplayName("모델이 JSON 뒤에 내용을 더 붙여도 첫 JSON 객체를 읽는다")
    void readsFirstJsonValue(String text) {
        assertThat(reader.read(text).path("summary").asString("")).isEqualTo("요약");
    }
}
