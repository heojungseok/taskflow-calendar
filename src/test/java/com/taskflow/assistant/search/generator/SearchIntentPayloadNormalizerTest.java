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
}
