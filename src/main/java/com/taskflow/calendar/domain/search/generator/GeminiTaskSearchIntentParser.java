package com.taskflow.calendar.domain.search.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.search.*;
import com.taskflow.calendar.domain.search.exception.TaskSearchGenerationException;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.common.ErrorCode;
import com.taskflow.config.GeminiSearchProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
@RequiredArgsConstructor
public class GeminiTaskSearchIntentParser implements TaskSearchIntentParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiTaskSearchIntentParser.class);

    private static final int MAX_TOPIC_TERMS = 5;
    private static final int MAX_PARTICIPANT_TERMS = 4;
    private static final int MAX_LOCATION_TERMS = 4;
    private static final int MAX_SUGGESTED_QUERIES = 3;
    private static final Set<String> TOPIC_STOPWORDS = Set.of(
            "일정",
            "작업",
            "업무",
            "할일",
            "할 일",
            "것"
    );
    private static final Set<String> LEISURE_TERMS = Set.of(
            "놀기",
            "놀이",
            "놀",
            "나들이",
            "여가",
            "데이트"
    );

    private final GeminiSearchProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public SearchIntent parse(String query) {
        validateConfiguration();

        String endpoint = properties.getBaseUrl().replaceAll("/$", "")
                + "/models/" + properties.getModel() + ":generateContent";
        String requestBody = buildRequestBody(query);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("x-goog-api-key", properties.getApiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        long startedAt = System.currentTimeMillis();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latencyMs = System.currentTimeMillis() - startedAt;
            if (response.statusCode() >= 400) {
                throw classifyUpstreamFailure(response.statusCode(), response.body(), latencyMs);
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode textNode = root.at("/candidates/0/content/parts/0/text");
            if (textNode.isMissingNode() || textNode.asText().isBlank()) {
                throw new TaskSearchGenerationException(
                        ErrorCode.LLM_INVALID_RESPONSE,
                        "Gemini search intent response did not contain text"
                );
            }

            JsonNode payload;
            try {
                payload = objectMapper.readTree(textNode.asText());
            } catch (IOException e) {
                throw new TaskSearchGenerationException(
                        ErrorCode.LLM_INVALID_RESPONSE,
                        "Gemini search intent payload was not valid JSON"
                );
            }

            log.info("Gemini search intent parsed. model={}, latencyMs={}, requestBodyLength={}",
                    properties.getModel(),
                    latencyMs,
                    requestBody.length());

            return normalize(query, payload);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskSearchGenerationException(
                    ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE,
                    "Gemini search intent request was interrupted"
            );
        } catch (IOException e) {
            throw new TaskSearchGenerationException(
                    ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE,
                    "Gemini search intent request failed: " + e.getMessage()
            );
        }
    }

    private void validateConfiguration() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new TaskSearchGenerationException(
                    ErrorCode.LLM_API_KEY_MISSING,
                    "GEMINI_SEARCH_API_KEY is not configured"
            );
        }
    }

    private String buildRequestBody(String query) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("system_instruction", createContent(systemInstruction()));
            requestBody.put("contents", List.of(createContent(userPrompt(query))));

            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("temperature", properties.getTemperature());
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseJsonSchema", responseJsonSchema());
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", 0));
            requestBody.put("generationConfig", generationConfig);

            return objectMapper.writeValueAsString(requestBody);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build Gemini search request body", e);
        }
    }

    private Map<String, Object> createContent(String text) {
        return Map.of("parts", List.of(Map.of("text", text)));
    }

    private String systemInstruction() {
        return "당신은 자연어 Task 검색 질의를 구조화하는 intent parsing 보조자다.\n"
                + "응답은 반드시 JSON 스키마를 따라라.\n"
                + "허용된 enum 값만 사용하라.\n"
                + "검색 결과 설명을 쓰지 말고 질의를 해석한 구조만 반환하라.\n"
                + "queryType과 relationPolicy는 서버가 다시 정규화하는 참고 힌트일 뿐이므로, 언어적으로만 보수적으로 판단하라.\n"
                + "genericCompanionRequired는 특정 인물명이 아니라 '누군가와', '누구랑', '같이', '함께', '동행'처럼 동행이 필요하다는 일반 조건이 있을 때만 true로 두어라.\n"
                + "genericCompanionRequired가 true인 경우 '누군가', '누구', '아무개' 같은 일반 표현은 participantTerms에 넣지 마라.\n"
                + "사람/장소/행동 조건이 함께 드러나는 복합 질의만 RELATIONAL_SEARCH로 두고, 애매하면 TOPIC_SEARCH를 우선하라.\n"
                + "막연한 탐색 질의만 BROAD_SEARCH다.\n"
                + "relationPolicy는 같은 일정 안에서 조건이 함께 성립해야 함이 분명할 때만 PREFER_ALL로 두고, 그 외에는 ALLOW_PARTIAL로 둬라.\n"
                + "suggestedQueries는 질의가 애매할 때 더 구체적인 재검색 문구를 최대 3개 작성하라.";
    }

    private String userPrompt(String query) {
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
                + "{\"queryType\":\"TOPIC_SEARCH\",\"targetType\":\"task\",\"domainType\":\"work\",\"mainAction\":\"meet\",\"secondaryActions\":[],\"topicTerms\":[\"화상 회의\"],\"participantTerms\":[],\"locationTerms\":[],\"genericCompanionRequired\":false,\"timeIntent\":\"upcoming\",\"priorityIntent\":\"none\",\"statusIntents\":[],\"syncIntent\":\"any\",\"relationPolicy\":\"ALLOW_PARTIAL\",\"overallConfidence\":0.9,\"fieldConfidence\":{\"targetType\":0.9,\"domainType\":0.8,\"mainAction\":0.9,\"topicTerms\":1.0,\"participantTerms\":0.5,\"locationTerms\":0.5,\"genericCompanionRequired\":0.2,\"timeIntent\":0.7,\"priorityIntent\":0.5,\"syncIntent\":0.5},\"suggestedQueries\":[]}\n"
                + "예시 2\n"
                + "query: 친구 만나서 병원 가는 거\n"
                + "{\"queryType\":\"RELATIONAL_SEARCH\",\"targetType\":\"task\",\"domainType\":\"personal\",\"mainAction\":\"visit\",\"secondaryActions\":[\"meet\"],\"topicTerms\":[],\"participantTerms\":[\"친구\"],\"locationTerms\":[\"병원\"],\"genericCompanionRequired\":false,\"timeIntent\":\"unspecified\",\"priorityIntent\":\"none\",\"statusIntents\":[],\"syncIntent\":\"any\",\"relationPolicy\":\"PREFER_ALL\",\"overallConfidence\":0.92,\"fieldConfidence\":{\"targetType\":0.9,\"domainType\":0.8,\"mainAction\":0.9,\"topicTerms\":0.5,\"participantTerms\":1.0,\"locationTerms\":1.0,\"genericCompanionRequired\":0.2,\"timeIntent\":0.5,\"priorityIntent\":0.5,\"syncIntent\":0.5},\"suggestedQueries\":[]}\n"
                + "예시 3\n"
                + "query: 누군가와 병원 가기\n"
                + "{\"queryType\":\"RELATIONAL_SEARCH\",\"targetType\":\"task\",\"domainType\":\"life\",\"mainAction\":\"visit\",\"secondaryActions\":[],\"topicTerms\":[],\"participantTerms\":[],\"locationTerms\":[\"병원\"],\"genericCompanionRequired\":true,\"timeIntent\":\"unspecified\",\"priorityIntent\":\"none\",\"statusIntents\":[],\"syncIntent\":\"any\",\"relationPolicy\":\"PREFER_ALL\",\"overallConfidence\":0.86,\"fieldConfidence\":{\"targetType\":0.9,\"domainType\":0.8,\"mainAction\":0.9,\"topicTerms\":0.3,\"participantTerms\":0.2,\"locationTerms\":1.0,\"genericCompanionRequired\":1.0,\"timeIntent\":0.5,\"priorityIntent\":0.5,\"syncIntent\":0.5},\"suggestedQueries\":[]}\n"
                + "예시 4\n"
                + "query: 중요한 일정\n"
                + "{\"queryType\":\"BROAD_SEARCH\",\"targetType\":\"mixed\",\"domainType\":\"unknown\",\"mainAction\":\"unknown\",\"secondaryActions\":[],\"topicTerms\":[],\"participantTerms\":[],\"locationTerms\":[],\"genericCompanionRequired\":false,\"timeIntent\":\"unspecified\",\"priorityIntent\":\"important\",\"statusIntents\":[],\"syncIntent\":\"any\",\"relationPolicy\":\"ALLOW_PARTIAL\",\"overallConfidence\":0.45,\"fieldConfidence\":{\"targetType\":0.8,\"domainType\":0.4,\"mainAction\":0.2,\"topicTerms\":0.2,\"participantTerms\":0.2,\"locationTerms\":0.2,\"genericCompanionRequired\":0.2,\"timeIntent\":0.4,\"priorityIntent\":0.9,\"syncIntent\":0.5},\"suggestedQueries\":[\"이번 주 마감 일정\",\"차단된 일정\",\"캘린더 반영 안 된 일정\"]}\n"
                + "query: " + query;
    }

    private Map<String, Object> responseJsonSchema() {
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
        actionItemField.put("enum", List.of("prepare", "submit", "buy", "visit", "meet", "organize", "fix", "check", "unknown"));

        Map<String, Object> timeIntentField = new LinkedHashMap<>(enumField);
        timeIntentField.put("enum", List.of("today", "this_week", "this_month", "upcoming", "recent", "overdue", "deferred", "unspecified"));

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
        schema.put("required", List.of(
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
                "suggestedQueries"
        ));
        schema.put("additionalProperties", false);
        return schema;
    }

    private SearchIntent normalize(String rawQuery, JsonNode payload) {
        SearchQueryType queryType = SearchQueryType.fromValue(payload.path("queryType").asText("TOPIC_SEARCH"));
        SearchTargetType targetType = SearchTargetType.fromValue(payload.path("targetType").asText());
        SearchDomainType domainType = SearchDomainType.fromValue(payload.path("domainType").asText());
        SearchActionIntent mainAction = SearchActionIntent.fromValue(payload.path("mainAction").asText());
        List<SearchActionIntent> secondaryActions = normalizeActionIntents(payload.path("secondaryActions"));
        List<String> topicTerms = normalizeTopicTerms(payload.path("topicTerms"));
        List<String> participantTerms = normalizeTerms(payload.path("participantTerms"), MAX_PARTICIPANT_TERMS);
        List<String> locationTerms = normalizeTerms(payload.path("locationTerms"), MAX_LOCATION_TERMS);
        boolean genericCompanionRequired = payload.path("genericCompanionRequired").asBoolean(false);
        SearchTimeIntent timeIntent = SearchTimeIntent.fromValue(payload.path("timeIntent").asText());
        SearchPriorityIntent priorityIntent = SearchPriorityIntent.fromValue(payload.path("priorityIntent").asText());
        List<TaskStatus> statusIntents = normalizeStatusIntents(payload.path("statusIntents"));
        SearchSyncIntent syncIntent = SearchSyncIntent.fromValue(payload.path("syncIntent").asText());
        SearchRelationPolicy relationPolicy = SearchRelationPolicy.fromValue(payload.path("relationPolicy").asText("ALLOW_PARTIAL"));
        double overallConfidence = clampConfidence(payload.path("overallConfidence").asDouble(0.0d));
        Map<String, Double> fieldConfidence = normalizeFieldConfidence(payload.path("fieldConfidence"));
        List<String> suggestedQueries = normalizeSuggestedQueries(payload.path("suggestedQueries"));

        if (mainAction == SearchActionIntent.UNKNOWN && containsLeisureTerm(topicTerms)) {
            mainAction = SearchActionIntent.MEET;
            fieldConfidence.putIfAbsent("mainAction", 0.72d);
            if (domainType == SearchDomainType.UNKNOWN) {
                domainType = SearchDomainType.PERSONAL;
                fieldConfidence.putIfAbsent("domainType", 0.68d);
            }
        }

        return SearchIntent.of(
                rawQuery,
                queryType,
                targetType,
                domainType,
                mainAction,
                secondaryActions,
                topicTerms,
                participantTerms,
                locationTerms,
                genericCompanionRequired,
                timeIntent,
                priorityIntent,
                statusIntents,
                syncIntent,
                relationPolicy,
                overallConfidence,
                fieldConfidence,
                suggestedQueries
        );
    }

    private List<SearchActionIntent> normalizeActionIntents(JsonNode node) {
        Set<SearchActionIntent> intents = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                SearchActionIntent intent = SearchActionIntent.fromValue(item.asText());
                if (intent != SearchActionIntent.UNKNOWN) {
                    intents.add(intent);
                }
            }
        }
        return new ArrayList<>(intents);
    }

    private List<String> normalizeTopicTerms(JsonNode node) {
        return normalizeTerms(node, MAX_TOPIC_TERMS);
    }

    private List<String> normalizeTerms(JsonNode node, int maxSize) {
        Set<String> terms = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String normalized = item.asText("").trim();
                if (!normalized.isBlank() && !isTopicStopword(normalized)) {
                    terms.add(normalized);
                }
                if (terms.size() >= maxSize) {
                    break;
                }
            }
        }
        return new ArrayList<>(terms);
    }

    private boolean isTopicStopword(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^0-9a-zA-Z가-힣\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return TOPIC_STOPWORDS.contains(normalized);
    }

    private boolean containsLeisureTerm(List<String> topicTerms) {
        for (String topicTerm : topicTerms) {
            String normalized = topicTerm == null ? "" : topicTerm.trim().toLowerCase(Locale.ROOT);
            if (LEISURE_TERMS.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private List<TaskStatus> normalizeStatusIntents(JsonNode node) {
        List<TaskStatus> statuses = new ArrayList<>();
        if (!node.isArray()) {
            return statuses;
        }
        for (JsonNode item : node) {
            try {
                statuses.add(TaskStatus.valueOf(item.asText().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return statuses;
    }

    private Map<String, Double> normalizeFieldConfidence(JsonNode node) {
        Map<String, Double> confidence = new LinkedHashMap<>();
        if (!node.isObject()) {
            return confidence;
        }
        node.fieldNames().forEachRemaining(field ->
                confidence.put(field, clampConfidence(node.path(field).asDouble(0.0d)))
        );
        return confidence;
    }

    private List<String> normalizeSuggestedQueries(JsonNode node) {
        Set<String> queries = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String normalized = item.asText("").trim();
                if (!normalized.isBlank()) {
                    queries.add(normalized);
                }
                if (queries.size() >= MAX_SUGGESTED_QUERIES) {
                    break;
                }
            }
        }
        return new ArrayList<>(queries);
    }

    private double clampConfidence(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private TaskSearchGenerationException classifyUpstreamFailure(int statusCode,
                                                                 String responseBody,
                                                                 long latencyMs) {
        ErrorCode errorCode;
        if (statusCode == 429) {
            String normalized = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
            if (normalized.contains("quota")) {
                errorCode = ErrorCode.LLM_QUOTA_EXHAUSTED;
            } else if (normalized.contains("rate")) {
                errorCode = ErrorCode.LLM_RATE_LIMITED_TEMPORARY;
            } else {
                errorCode = ErrorCode.LLM_429_UNKNOWN;
            }
        } else if (statusCode == 400 || statusCode == 404) {
            errorCode = ErrorCode.LLM_CONFIG_INVALID;
        } else {
            errorCode = ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE;
        }

        log.warn("Gemini search intent request failed. statusCode={}, errorCode={}, latencyMs={}, body={}",
                statusCode,
                errorCode.getCode(),
                latencyMs,
                abbreviate(responseBody));

        return new TaskSearchGenerationException(
                errorCode,
                "Gemini search intent request failed: " + abbreviate(responseBody)
        );
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }
}
