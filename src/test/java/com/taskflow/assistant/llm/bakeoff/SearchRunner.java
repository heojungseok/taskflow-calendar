package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.assistant.llm.LlmJsonReader;
import com.taskflow.assistant.llm.bakeoff.BakeoffFixtures.SearchCase;
import com.taskflow.assistant.search.SearchIntent;
import com.taskflow.planning.task.TaskStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class SearchRunner implements FeatureRunner {

    private static final String AFTER_NORMALIZATION = " (운영 정규화 후)";
    private static final List<MetricColumn> COLUMNS = List.of(
            new MetricColumn("rawEnumViolations", "스키마 밖 enum 값(개/호출, 원문)", false),
            new MetricColumn("acc_targetType", "targetType" + AFTER_NORMALIZATION, true),
            new MetricColumn("acc_timeIntent", "timeIntent" + AFTER_NORMALIZATION, true),
            new MetricColumn("acc_priorityIntent", "priority" + AFTER_NORMALIZATION, true),
            new MetricColumn("acc_mainAction", "mainAction" + AFTER_NORMALIZATION, true),
            new MetricColumn("acc_statusIntents", "status" + AFTER_NORMALIZATION, true),
            new MetricColumn("fieldAccuracy", "전체 정확도" + AFTER_NORMALIZATION, true),
            new MetricColumn("nonDefaultAccuracy", "기본값 아닌 정답만 정확도" + AFTER_NORMALIZATION, true),
            new MetricColumn("topicRecall", "topic 재현율" + AFTER_NORMALIZATION, true));
    private static final List<String> FIELDS =
            List.of("acc_targetType", "acc_timeIntent", "acc_priorityIntent", "acc_mainAction", "acc_statusIntents");
    private static final Actual DEFAULTS = new Actual("task", "unspecified", "none", "unknown", Set.of());
    private static final Map<String, Set<String>> SCHEMA_ENUMS = Map.of(
            "targetType", Set.of("task", "project", "mixed"),
            "mainAction", Set.of("prepare", "submit", "buy", "visit", "meet", "organize", "fix", "check", "unknown"),
            "timeIntent",
                    Set.of(
                            "today",
                            "this_week",
                            "this_month",
                            "upcoming",
                            "recent",
                            "overdue",
                            "deferred",
                            "unspecified"),
            "priorityIntent", Set.of("urgent", "important", "must_do", "deferred", "none"));
    private static final Set<String> STATUS_ENUM = Set.of("REQUESTED", "IN_PROGRESS", "BLOCKED", "DONE");

    private final List<SearchCase> cases;
    private final LlmJsonReader jsonReader;

    SearchRunner(List<SearchCase> cases, ObjectMapper objectMapper) {
        this.cases = cases;
        this.jsonReader = new LlmJsonReader(objectMapper);
    }

    @Override
    public String feature() {
        return "search";
    }

    @Override
    public List<String> caseIds() {
        return cases.stream().map(SearchCase::id).toList();
    }

    @Override
    public List<MetricColumn> columns() {
        return COLUMNS;
    }

    String query(int caseIndex) {
        return cases.get(caseIndex).query();
    }

    @Override
    public List<Map<String, Double>> baselineMetrics() {
        return cases.stream().map(expected -> fieldMetrics(expected, DEFAULTS)).toList();
    }

    @Override
    public Outcome run(int caseIndex, Generators generators) {
        SearchCase expected = cases.get(caseIndex);
        SearchIntent intent = generators.search().parse(expected.query());
        String rawText = MeteredLlmProvider.lastText();
        Map<String, Double> metrics = fieldMetrics(expected, actual(intent));
        metrics.put("rawEnumViolations", (double) enumViolations(rawText));
        if (!expected.expectedTopicKeywords().isEmpty()) {
            metrics.put("topicRecall", topicRecall(expected.expectedTopicKeywords(), intent));
        }
        return new Outcome(output(intent, rawText), metrics);
    }

    private Map<String, Double> fieldMetrics(SearchCase expected, Actual actual) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("acc_targetType", hit(expected.targetType(), actual.targetType()));
        metrics.put("acc_timeIntent", hit(expected.timeIntent(), actual.timeIntent()));
        metrics.put("acc_priorityIntent", hit(expected.priorityIntent(), actual.priority()));
        metrics.put("acc_mainAction", hit(expected.mainAction(), actual.mainAction()));
        metrics.put("acc_statusIntents", statusHit(expected.statusIntents(), actual.statuses()));
        metrics.put("fieldAccuracy", mean(metrics, FIELDS));
        List<String> nonDefault = nonDefaultFields(expected);
        if (!nonDefault.isEmpty()) {
            metrics.put("nonDefaultAccuracy", mean(metrics, nonDefault));
        }
        return metrics;
    }

    private static List<String> nonDefaultFields(SearchCase expected) {
        List<String> fields = new ArrayList<>();
        if (!expected.targetType().contains(DEFAULTS.targetType())) {
            fields.add("acc_targetType");
        }
        if (!expected.timeIntent().contains(DEFAULTS.timeIntent())) {
            fields.add("acc_timeIntent");
        }
        if (!expected.priorityIntent().contains(DEFAULTS.priority())) {
            fields.add("acc_priorityIntent");
        }
        if (!expected.mainAction().contains(DEFAULTS.mainAction())) {
            fields.add("acc_mainAction");
        }
        if (!expected.statusIntents().isEmpty()) {
            fields.add("acc_statusIntents");
        }
        return fields;
    }

    private static double mean(Map<String, Double> metrics, List<String> keys) {
        return keys.stream().mapToDouble(metrics::get).average().orElse(0);
    }

    private int enumViolations(String rawText) {
        JsonNode payload = jsonReader.read(rawText);
        int violations = 0;
        for (Map.Entry<String, Set<String>> entry : SCHEMA_ENUMS.entrySet()) {
            if (!entry.getValue().contains(payload.path(entry.getKey()).asString(""))) {
                violations++;
            }
        }
        for (JsonNode status : payload.path("statusIntents")) {
            if (!STATUS_ENUM.contains(status.asString(""))) {
                violations++;
            }
        }
        return violations;
    }

    private static Actual actual(SearchIntent intent) {
        Set<String> statuses = new TreeSet<>();
        for (TaskStatus status : intent.getStatusIntents()) {
            statuses.add(status.name().toLowerCase(Locale.ROOT));
        }
        return new Actual(
                intent.getTargetType().name().toLowerCase(Locale.ROOT),
                intent.getTimeIntent().name().toLowerCase(Locale.ROOT),
                intent.getPriorityIntent().name().toLowerCase(Locale.ROOT),
                intent.getMainAction().name().toLowerCase(Locale.ROOT),
                statuses);
    }

    private static double hit(List<String> allowed, String actual) {
        return allowed.contains(actual) ? 1.0 : 0.0;
    }

    private static double statusHit(List<String> expected, Set<String> actual) {
        Set<String> expectedSet = new TreeSet<>();
        for (String status : expected) {
            expectedSet.add(status.toLowerCase(Locale.ROOT));
        }
        return expectedSet.equals(actual) ? 1.0 : 0.0;
    }

    private static double topicRecall(List<String> keywords, SearchIntent intent) {
        List<String> haystack = new ArrayList<>(intent.getTopicTerms());
        haystack.addAll(intent.getSuggestedQueries());
        int found = 0;
        for (String keyword : keywords) {
            for (String text : haystack) {
                if (TextMetrics.compact(text).contains(TextMetrics.compact(keyword))) {
                    found++;
                    break;
                }
            }
        }
        return (double) found / keywords.size();
    }

    private static Map<String, Object> output(SearchIntent intent, String rawText) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("rawText", rawText);
        output.put("targetType", intent.getTargetType());
        output.put("domainType", intent.getDomainType());
        output.put("mainAction", intent.getMainAction());
        output.put("secondaryActions", intent.getSecondaryActions());
        output.put("topicTerms", intent.getTopicTerms());
        output.put("participantTerms", intent.getParticipantTerms());
        output.put("locationTerms", intent.getLocationTerms());
        output.put("timeIntent", intent.getTimeIntent());
        output.put("priorityIntent", intent.getPriorityIntent());
        output.put("statusIntents", intent.getStatusIntents());
        output.put("suggestedQueries", intent.getSuggestedQueries());
        output.put("overallConfidence", intent.getOverallConfidence());
        return output;
    }

    private record Actual(
            String targetType, String timeIntent, String priority, String mainAction, Set<String> statuses) {}
}
