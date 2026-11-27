package com.taskflow.assistant.search.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskflow.assistant.search.SearchIntent;
import com.taskflow.assistant.search.SearchTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SearchIntentPayloadNormalizerTest {

    @Test
    @DisplayName("Gemini가 문자열 칸에 객체·배열을 넣어도 기본값으로 정규화한다")
    void nonTextValuesFallBackToDefaults() {
        String payload = """
                {"targetType":{"x":1},"domainType":["WORK"],"mainAction":{},"timeIntent":[],\
                "priorityIntent":{},"syncIntent":[1],"secondaryActions":[{}],"statusIntents":[[]]}""";

        SearchIntent intent = SearchIntentPayloadNormalizer.normalize(
                "배포", JsonMapper.builder().build().readTree(payload));

        assertThat(intent.getTargetType()).isEqualTo(SearchTargetType.MIXED);
    }

    @Test
    @DisplayName("fieldConfidence는 스키마에 있는 키만 남기고 모델이 덧붙인 키는 버린다")
    void fieldConfidenceKeepsOnlySchemaKeys() {
        String payload = """
                {"fieldConfidence":{"targetType":0.9,"syncIntent":0.4,"unknownKey":0.7,"queryType":0.5}}""";

        SearchIntent intent = SearchIntentPayloadNormalizer.normalize(
                "배포", JsonMapper.builder().build().readTree(payload));

        assertThat(intent.getFieldConfidence())
                .containsEntry("targetType", 0.9d)
                .containsEntry("syncIntent", 0.4d);
        assertThat(intent.getFieldConfidence()).doesNotContainKeys("unknownKey", "queryType");
    }
}
