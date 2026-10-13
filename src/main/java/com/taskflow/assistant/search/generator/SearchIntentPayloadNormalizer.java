package com.taskflow.assistant.search.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskflow.assistant.search.*;
import com.taskflow.planning.task.TaskStatus;
import java.util.*;

/** Gemini가 돌려준 검색 의도 JSON을 허용 값·개수·신뢰도 범위 안으로 정규화한다. */
final class SearchIntentPayloadNormalizer {

    private static final int MAX_TOPIC_TERMS = 5;
    private static final int MAX_PARTICIPANT_TERMS = 4;
    private static final int MAX_LOCATION_TERMS = 4;
    private static final int MAX_SUGGESTED_QUERIES = 3;
    private static final Set<String> TOPIC_STOPWORDS = Set.of("일정", "작업", "업무", "할일", "할 일", "것");
    private static final Set<String> LEISURE_TERMS = Set.of("놀기", "놀이", "놀", "나들이", "여가", "데이트");

    private SearchIntentPayloadNormalizer() {}

    static SearchIntent normalize(String rawQuery, JsonNode payload) {
        SearchQueryType queryType =
                SearchQueryType.fromValue(payload.path("queryType").asText("TOPIC_SEARCH"));
        SearchTargetType targetType =
                SearchTargetType.fromValue(payload.path("targetType").asText());
        SearchDomainType domainType =
                SearchDomainType.fromValue(payload.path("domainType").asText());
        SearchActionIntent mainAction =
                SearchActionIntent.fromValue(payload.path("mainAction").asText());
        List<SearchActionIntent> secondaryActions = normalizeActionIntents(payload.path("secondaryActions"));
        List<String> topicTerms = normalizeTopicTerms(payload.path("topicTerms"));
        List<String> participantTerms = normalizeTerms(payload.path("participantTerms"), MAX_PARTICIPANT_TERMS);
        List<String> locationTerms = normalizeTerms(payload.path("locationTerms"), MAX_LOCATION_TERMS);
        boolean genericCompanionRequired =
                payload.path("genericCompanionRequired").asBoolean(false);
        SearchTimeIntent timeIntent =
                SearchTimeIntent.fromValue(payload.path("timeIntent").asText());
        SearchPriorityIntent priorityIntent =
                SearchPriorityIntent.fromValue(payload.path("priorityIntent").asText());
        List<TaskStatus> statusIntents = normalizeStatusIntents(payload.path("statusIntents"));
        SearchSyncIntent syncIntent =
                SearchSyncIntent.fromValue(payload.path("syncIntent").asText());
        SearchRelationPolicy relationPolicy =
                SearchRelationPolicy.fromValue(payload.path("relationPolicy").asText("ALLOW_PARTIAL"));
        double overallConfidence =
                clampConfidence(payload.path("overallConfidence").asDouble(0.0d));
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
                suggestedQueries);
    }

    private static List<SearchActionIntent> normalizeActionIntents(JsonNode node) {
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

    private static List<String> normalizeTopicTerms(JsonNode node) {
        return normalizeTerms(node, MAX_TOPIC_TERMS);
    }

    private static List<String> normalizeTerms(JsonNode node, int maxSize) {
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

    private static boolean isTopicStopword(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^0-9a-zA-Z가-힣\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return TOPIC_STOPWORDS.contains(normalized);
    }

    private static boolean containsLeisureTerm(List<String> topicTerms) {
        for (String topicTerm : topicTerms) {
            String normalized = topicTerm == null ? "" : topicTerm.trim().toLowerCase(Locale.ROOT);
            if (LEISURE_TERMS.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static List<TaskStatus> normalizeStatusIntents(JsonNode node) {
        List<TaskStatus> statuses = new ArrayList<>();
        if (!node.isArray()) {
            return statuses;
        }
        for (JsonNode item : node) {
            // 모델이 모르는 상태값을 주면 버린다
            String name = item.asText().trim().toUpperCase(Locale.ROOT);
            Arrays.stream(TaskStatus.values())
                    .filter(status -> status.name().equals(name))
                    .findFirst()
                    .ifPresent(statuses::add);
        }
        return statuses;
    }

    private static Map<String, Double> normalizeFieldConfidence(JsonNode node) {
        Map<String, Double> confidence = new LinkedHashMap<>();
        if (!node.isObject()) {
            return confidence;
        }
        node.fieldNames()
                .forEachRemaining(field ->
                        confidence.put(field, clampConfidence(node.path(field).asDouble(0.0d))));
        return confidence;
    }

    private static List<String> normalizeSuggestedQueries(JsonNode node) {
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

    private static double clampConfidence(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
