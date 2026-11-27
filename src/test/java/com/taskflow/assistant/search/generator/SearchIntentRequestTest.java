package com.taskflow.assistant.search.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskflow.assistant.llm.LlmFeature;
import com.taskflow.assistant.llm.LlmRequest;
import com.taskflow.assistant.search.GeminiSearchProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SearchIntentRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmRequest request = SearchIntentRequest.build(new GeminiSearchProperties(), "이번 주 배포");
    private final JsonNode schema = objectMapper.valueToTree(request.responseSchema());

    @Test
    @DisplayName("검색 의도 요청은 기능, 온도, 시스템 지시, 질의가 담긴 프롬프트, 스키마를 담는다")
    void buildsSearchIntentRequest() {
        GeminiSearchProperties properties = new GeminiSearchProperties();

        assertThat(request.feature()).isEqualTo(LlmFeature.SEARCH_INTENT);
        assertThat(request.temperature()).isEqualTo(properties.getTemperature());
        assertThat(request.topK()).isNull();
        assertThat(request.topP()).isNull();
        assertThat(request.systemInstruction()).startsWith("당신은 자연어 Task 검색 질의를 구조화하는 intent parsing 보조자다.");
        assertThat(request.userPrompt()).startsWith("아래 자연어 검색 질의를 Task 검색 intent로 구조화하라.");
        assertThat(request.userPrompt()).endsWith("query: 이번 주 배포");
        assertThat(request.responseSchema()).containsKeys("properties", "required");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "timeIntent, unspecified",
        "priorityIntent, none",
        "targetType, task",
        "mainAction, unknown",
        "statusIntents, []"
    })
    @DisplayName("예시는 의도 필드마다 기본값이 아닌 값을 한 번 이상 보여 준다")
    void examplesShowNonDefaultValueForEachIntentField(String field, String defaultValue) {
        List<String> values =
                examples().stream().map(example -> text(example.path(field))).toList();

        assertThat(values).anyMatch(value -> !value.equals(defaultValue));
    }

    @Test
    @DisplayName("시간 표현이 없는 회의 예시는 timeIntent를 기본값으로 둔다")
    void meetingExampleWithoutTimeExpressionKeepsDefaultTime() {
        assertThat(examples().getFirst().path("timeIntent").asString()).isEqualTo("unspecified");
    }

    @Test
    @DisplayName("예시는 스키마의 필수 필드를 모두 갖고 enum 값은 스키마가 허용하는 값만 쓴다")
    void examplesFollowResponseSchema() {
        Set<String> required = new TreeSet<>(strings(schema.path("required")));

        for (JsonNode example : examples()) {
            String name = example.path("queryType").asString();
            assertThat(new TreeSet<>(example.propertyNames())).as(name).isEqualTo(required);
            for (Map.Entry<String, JsonNode> property :
                    schema.path("properties").properties()) {
                assertWithinEnum(name, property.getKey(), property.getValue(), example.path(property.getKey()));
            }
        }
    }

    private void assertWithinEnum(String name, String field, JsonNode spec, JsonNode value) {
        if (spec.has("enum")) {
            assertThat(strings(spec.path("enum"))).as(name + "/" + field).contains(value.asString());
        }
        if (spec.path("items").has("enum")) {
            for (JsonNode item : value) {
                assertThat(strings(spec.path("items").path("enum")))
                        .as(name + "/" + field)
                        .contains(item.asString());
            }
        }
    }

    private List<JsonNode> examples() {
        List<JsonNode> examples = new ArrayList<>();
        for (String line : request.userPrompt().split("\n")) {
            if (line.startsWith("{")) {
                examples.add(objectMapper.readTree(line));
            }
        }
        assertThat(examples).isNotEmpty();
        return examples;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            values.add(node.asString());
        }
        return values;
    }

    private static String text(JsonNode node) {
        assertThat(node.isMissingNode()).isFalse();
        return node.isArray() ? node.toString() : node.asString();
    }
}
