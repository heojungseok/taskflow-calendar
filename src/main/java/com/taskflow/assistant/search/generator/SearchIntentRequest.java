package com.taskflow.assistant.search.generator;

import com.taskflow.assistant.llm.LlmFeature;
import com.taskflow.assistant.llm.LlmRequest;
import com.taskflow.assistant.search.*;
import com.taskflow.assistant.search.GeminiSearchProperties;
import java.util.*;

/** 검색 의도 파싱용 LLM 요청: 시스템 지시, 예시가 담긴 사용자 프롬프트, 응답 스키마. */
final class SearchIntentRequest {

    private SearchIntentRequest() {}

    static LlmRequest build(GeminiSearchProperties properties, String query) {
        return new LlmRequest(
                LlmFeature.SEARCH_INTENT,
                systemInstruction(),
                userPrompt(query),
                responseJsonSchema(),
                properties.getTemperature(),
                null,
                null);
    }

    private static String systemInstruction() {
        return """
               당신은 자연어 Task 검색 질의를 구조화하는 intent parsing 보조자다.
               응답은 반드시 JSON 스키마를 따라라.
               허용된 enum 값만 사용하라.
               검색 결과 설명을 쓰지 말고 질의를 해석한 구조만 반환하라.
               queryType과 relationPolicy는 서버가 다시 정규화하는 참고 힌트일 뿐이므로, 언어적으로만 보수적으로 판단하라.
               genericCompanionRequired는 특정 인물명이 아니라 '누군가와', '누구랑', '같이', '함께', '동행'처럼 동행이 필요하다는 일반 조건이 있을 때만 true로 두어라.
               genericCompanionRequired가 true인 경우 '누군가', '누구', '아무개' 같은 일반 표현은 participantTerms에 넣지 마라.
               사람/장소/행동 조건이 함께 드러나는 복합 질의만 RELATIONAL_SEARCH로 두고, 애매하면 TOPIC_SEARCH를 우선하라.
               막연한 탐색 질의만 BROAD_SEARCH다.
               relationPolicy는 같은 일정 안에서 조건이 함께 성립해야 함이 분명할 때만 PREFER_ALL로 두고, 그 외에는 ALLOW_PARTIAL로 둬라.
               suggestedQueries는 질의가 애매할 때 더 구체적인 재검색 문구를 최대 3개 작성하라.
               아래 필드는 질의에 해당 표현이 있을 때만 값을 정하고, 없으면 기본값을 둬라.
               timeIntent(기본 unspecified): today=오늘, this_week=이번 주(까지·안에 포함), this_month=이번 달(말까지 포함), upcoming=곧·다가오는·앞으로, recent=최근·요즘, overdue=마감·기한이 이미 지난 것이나 이미 했어야 하는 것('오늘 마감'처럼 마감일만 말하면 그 시간 값), deferred=미루거나 보류한 것. '이번 주'는 upcoming이 아니라 this_week다.
               statusIntents(기본 []): REQUESTED=아직 시작 안 한·요청된, IN_PROGRESS=진행 중·하고 있는, BLOCKED=막힌·못 하고 있는, DONE=완료한·끝난. 상태 표현이 있으면 반드시 넣어라.
               priorityIntent(기본 none): urgent=급한, important=중요한, must_do=꼭·반드시, deferred=나중에 해도 되는. '~해야 하는'만으로는 none이다.
               targetType(기본 task): 질의가 프로젝트 자체를 찾으면 project, '일정·할 일·작업·건'처럼 Task를 가리키면 task, 무엇을 찾는지 전혀 말하지 않으면 mixed.
               mainAction(기본 unknown): 행동을 뜻하는 동사나 동작 명사('~하다'를 붙일 수 있는 명사)가 enum 행동에 해당할 때만 고르고, 대상 명사만 있거나 enum에 없는 행동이면 unknown.\
               """;
    }

    private static String userPrompt(String query) {
        return "아래 자연어 검색 질의를 Task 검색 intent로 구조화하라.\n"
                + "topicTerms는 '일정', '작업', '업무', '할 일' 같은 범용어를 제외한 핵심 주제어만 0~5개로 줄여라.\n"
                + "mainAction은 질의의 중심 행동 하나만 선택하라.\n"
                + "secondaryActions는 부가 행동만 넣어라.\n"
                + "participantTerms는 사람/동행/주체 조건을 넣어라.\n"
                + "locationTerms는 장소/기관/방문 대상을 넣어라.\n"
                + "동행 상대가 특정되지 않은 일반 표현은 participantTerms가 아니라 genericCompanionRequired=true로 표현하라.\n"
                + "queryType과 relationPolicy는 검색 정책이 아니라 언어 해석 힌트로만 판단하라.\n"
                + "fieldConfidence는 targetType, domainType, mainAction, topicTerms, participantTerms, locationTerms, genericCompanionRequired, timeIntent, priorityIntent, syncIntent 각 항목의 0~1 신뢰도를 넣어라.\n"
                + "예시 1\n"
                + "query: 화상 회의 일정들\n"
                + "{\"queryType\":\"TOPIC_SEARCH\",\"targetType\":\"task\",\"domainType\":\"work\",\"mainAction\":\"meet\",\"secondaryActions\":[],\"topicTerms\":[\"화상 회의\"],\"participantTerms\":[],\"locationTerms\":[],\"genericCompanionRequired\":false,\"timeIntent\":\"unspecified\",\"priorityIntent\":\"none\",\"statusIntents\":[],\"syncIntent\":\"any\",\"relationPolicy\":\"ALLOW_PARTIAL\",\"overallConfidence\":0.9,\"fieldConfidence\":{\"targetType\":0.9,\"domainType\":0.8,\"mainAction\":0.9,\"topicTerms\":1.0,\"participantTerms\":0.5,\"locationTerms\":0.5,\"genericCompanionRequired\":0.2,\"timeIntent\":0.7,\"priorityIntent\":0.5,\"syncIntent\":0.5},\"suggestedQueries\":[]}\n"
                + "예시 2\n"
                + "query: 친구 만나서 병원 가는 거\n"
                + "{\"queryType\":\"RELATIONAL_SEARCH\",\"targetType\":\"task\",\"domainType\":\"personal\",\"mainAction\":\"visit\",\"secondaryActions\":[\"meet\"],\"topicTerms\":[],\"participantTerms\":[\"친구\"],\"locationTerms\":[\"병원\"],\"genericCompanionRequired\":false,\"timeIntent\":\"unspecified\",\"priorityIntent\":\"none\",\"statusIntents\":[],\"syncIntent\":\"any\",\"relationPolicy\":\"PREFER_ALL\",\"overallConfidence\":0.92,\"fieldConfidence\":{\"targetType\":0.9,\"domainType\":0.8,\"mainAction\":0.9,\"topicTerms\":0.5,\"participantTerms\":1.0,\"locationTerms\":1.0,\"genericCompanionRequired\":0.2,\"timeIntent\":0.5,\"priorityIntent\":0.5,\"syncIntent\":0.5},\"suggestedQueries\":[]}\n"
                + "예시 3\n"
                + "query: 누군가와 병원 가기\n"
                + "{\"queryType\":\"RELATIONAL_SEARCH\",\"targetType\":\"task\",\"domainType\":\"life\",\"mainAction\":\"visit\",\"secondaryActions\":[],\"topicTerms\":[],\"participantTerms\":[],\"locationTerms\":[\"병원\"],\"genericCompanionRequired\":true,\"timeIntent\":\"unspecified\",\"priorityIntent\":\"none\",\"statusIntents\":[],\"syncIntent\":\"any\",\"relationPolicy\":\"PREFER_ALL\",\"overallConfidence\":0.86,\"fieldConfidence\":{\"targetType\":0.9,\"domainType\":0.8,\"mainAction\":0.9,\"topicTerms\":0.3,\"participantTerms\":0.2,\"locationTerms\":1.0,\"genericCompanionRequired\":1.0,\"timeIntent\":0.5,\"priorityIntent\":0.5,\"syncIntent\":0.5},\"suggestedQueries\":[]}\n"
                + "예시 4\n"
                + "query: 중요한 일정\n"
                + "{\"queryType\":\"BROAD_SEARCH\",\"targetType\":\"task\",\"domainType\":\"unknown\",\"mainAction\":\"unknown\",\"secondaryActions\":[],\"topicTerms\":[],\"participantTerms\":[],\"locationTerms\":[],\"genericCompanionRequired\":false,\"timeIntent\":\"unspecified\",\"priorityIntent\":\"important\",\"statusIntents\":[],\"syncIntent\":\"any\",\"relationPolicy\":\"ALLOW_PARTIAL\",\"overallConfidence\":0.45,\"fieldConfidence\":{\"targetType\":0.8,\"domainType\":0.4,\"mainAction\":0.2,\"topicTerms\":0.2,\"participantTerms\":0.2,\"locationTerms\":0.2,\"genericCompanionRequired\":0.2,\"timeIntent\":0.4,\"priorityIntent\":0.9,\"syncIntent\":0.5},\"suggestedQueries\":[\"이번 주 마감 일정\",\"차단된 일정\",\"캘린더 반영 안 된 일정\"]}\n"
                + "예시 5\n"
                + "query: 어제 끝냈어야 했는데 막힌 견적서\n"
                + "{\"queryType\":\"TOPIC_SEARCH\",\"targetType\":\"task\",\"domainType\":\"work\",\"mainAction\":\"unknown\",\"secondaryActions\":[],\"topicTerms\":[\"견적서\"],\"participantTerms\":[],\"locationTerms\":[],\"genericCompanionRequired\":false,\"timeIntent\":\"overdue\",\"priorityIntent\":\"none\",\"statusIntents\":[\"BLOCKED\"],\"syncIntent\":\"any\",\"relationPolicy\":\"ALLOW_PARTIAL\",\"overallConfidence\":0.85,\"fieldConfidence\":{\"targetType\":0.9,\"domainType\":0.6,\"mainAction\":0.6,\"topicTerms\":0.9,\"participantTerms\":0.5,\"locationTerms\":0.5,\"genericCompanionRequired\":0.2,\"timeIntent\":0.9,\"priorityIntent\":0.6,\"syncIntent\":0.5},\"suggestedQueries\":[]}\n"
                + "예시 6\n"
                + "query: 앱 리뉴얼 관련 프로젝트\n"
                + "{\"queryType\":\"TOPIC_SEARCH\",\"targetType\":\"project\",\"domainType\":\"work\",\"mainAction\":\"unknown\",\"secondaryActions\":[],\"topicTerms\":[\"앱\",\"리뉴얼\"],\"participantTerms\":[],\"locationTerms\":[],\"genericCompanionRequired\":false,\"timeIntent\":\"unspecified\",\"priorityIntent\":\"none\",\"statusIntents\":[],\"syncIntent\":\"any\",\"relationPolicy\":\"ALLOW_PARTIAL\",\"overallConfidence\":0.85,\"fieldConfidence\":{\"targetType\":0.9,\"domainType\":0.6,\"mainAction\":0.5,\"topicTerms\":0.9,\"participantTerms\":0.5,\"locationTerms\":0.5,\"genericCompanionRequired\":0.2,\"timeIntent\":0.5,\"priorityIntent\":0.5,\"syncIntent\":0.5},\"suggestedQueries\":[]}\n"
                + "query: " + query;
    }

    private static Map<String, Object> responseJsonSchema() {
        Map<String, Object> stringField = Map.of("type", "string");
        Map<String, Object> numberField = Map.of("type", "number");

        Map<String, Object> enumField = new LinkedHashMap<>();
        enumField.put("type", "string");

        Map<String, Object> targetTypeField = new LinkedHashMap<>(enumField);
        targetTypeField.put("enum", List.of("task", "project", "mixed"));

        Map<String, Object> queryTypeField = new LinkedHashMap<>(enumField);
        queryTypeField.put("enum", List.of("TOPIC_SEARCH", "RELATIONAL_SEARCH", "BROAD_SEARCH"));

        Map<String, Object> domainTypeField = new LinkedHashMap<>(enumField);
        domainTypeField.put("enum", List.of("work", "personal", "life", "mixed", "unknown"));

        Map<String, Object> actionItemField = new LinkedHashMap<>(enumField);
        actionItemField.put(
                "enum", List.of("prepare", "submit", "buy", "visit", "meet", "organize", "fix", "check", "unknown"));

        Map<String, Object> timeIntentField = new LinkedHashMap<>(enumField);
        timeIntentField.put(
                "enum",
                List.of(
                        "today",
                        "this_week",
                        "this_month",
                        "upcoming",
                        "recent",
                        "overdue",
                        "deferred",
                        "unspecified"));

        Map<String, Object> priorityIntentField = new LinkedHashMap<>(enumField);
        priorityIntentField.put("enum", List.of("urgent", "important", "must_do", "deferred", "none"));

        Map<String, Object> statusItemField = new LinkedHashMap<>(enumField);
        statusItemField.put("enum", List.of("REQUESTED", "IN_PROGRESS", "BLOCKED", "DONE"));

        Map<String, Object> syncIntentField = new LinkedHashMap<>(enumField);
        syncIntentField.put("enum", List.of("synced", "unsynced", "failed", "any"));

        Map<String, Object> actionArray = new LinkedHashMap<>();
        actionArray.put("type", "array");
        actionArray.put("items", actionItemField);

        Map<String, Object> stringArray = new LinkedHashMap<>();
        stringArray.put("type", "array");
        stringArray.put("items", stringField);

        Map<String, Object> statusArray = new LinkedHashMap<>();
        statusArray.put("type", "array");
        statusArray.put("items", statusItemField);

        Map<String, Object> fieldConfidenceProperties = new LinkedHashMap<>();
        fieldConfidenceProperties.put("targetType", numberField);
        fieldConfidenceProperties.put("domainType", numberField);
        fieldConfidenceProperties.put("mainAction", numberField);
        fieldConfidenceProperties.put("topicTerms", numberField);
        fieldConfidenceProperties.put("participantTerms", numberField);
        fieldConfidenceProperties.put("locationTerms", numberField);
        fieldConfidenceProperties.put("genericCompanionRequired", numberField);
        fieldConfidenceProperties.put("timeIntent", numberField);
        fieldConfidenceProperties.put("priorityIntent", numberField);
        fieldConfidenceProperties.put("syncIntent", numberField);

        Map<String, Object> fieldConfidenceField = new LinkedHashMap<>();
        fieldConfidenceField.put("type", "object");
        fieldConfidenceField.put("properties", fieldConfidenceProperties);
        fieldConfidenceField.put("additionalProperties", false);

        Map<String, Object> relationPolicyField = new LinkedHashMap<>(enumField);
        relationPolicyField.put("enum", List.of("PREFER_ALL", "ALLOW_PARTIAL"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("queryType", queryTypeField);
        properties.put("targetType", targetTypeField);
        properties.put("domainType", domainTypeField);
        properties.put("mainAction", actionItemField);
        properties.put("secondaryActions", actionArray);
        properties.put("topicTerms", stringArray);
        properties.put("participantTerms", stringArray);
        properties.put("locationTerms", stringArray);
        properties.put("genericCompanionRequired", Map.of("type", "boolean"));
        properties.put("timeIntent", timeIntentField);
        properties.put("priorityIntent", priorityIntentField);
        properties.put("statusIntents", statusArray);
        properties.put("syncIntent", syncIntentField);
        properties.put("relationPolicy", relationPolicyField);
        properties.put("overallConfidence", numberField);
        properties.put("fieldConfidence", fieldConfidenceField);
        properties.put("suggestedQueries", stringArray);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put(
                "required",
                List.of(
                        "targetType",
                        "queryType",
                        "domainType",
                        "mainAction",
                        "secondaryActions",
                        "topicTerms",
                        "participantTerms",
                        "locationTerms",
                        "genericCompanionRequired",
                        "timeIntent",
                        "priorityIntent",
                        "statusIntents",
                        "syncIntent",
                        "relationPolicy",
                        "overallConfidence",
                        "fieldConfidence",
                        "suggestedQueries"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
