package com.taskflow.assistant.llm.bakeoff;

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

final class SearchRunner implements FeatureRunner {

    private static final List<MetricColumn> COLUMNS = List.of(
            new MetricColumn("acc_targetType", "targetType", true),
            new MetricColumn("acc_timeIntent", "timeIntent", true),
            new MetricColumn("acc_priorityIntent", "priority", true),
            new MetricColumn("acc_mainAction", "mainAction", true),
            new MetricColumn("acc_statusIntents", "status", true),
            new MetricColumn("fieldAccuracy", "전체 정확도", true),
            new MetricColumn("topicRecall", "topic 재현율", true));

    private final List<SearchCase> cases;

    SearchRunner(List<SearchCase> cases) {
        this.cases = cases;
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
    public Outcome run(int caseIndex, Generators generators) {
        SearchCase expected = cases.get(caseIndex);
        SearchIntent intent = generators.search().parse(expected.query());
        return new Outcome(output(intent), score(expected, intent));
    }

    private Map<String, Double> score(SearchCase expected, SearchIntent intent) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put(
                "acc_targetType",
                hit(expected.targetType(), intent.getTargetType().name()));
        metrics.put(
                "acc_timeIntent",
                hit(expected.timeIntent(), intent.getTimeIntent().name()));
        metrics.put(
                "acc_priorityIntent",
                hit(expected.priorityIntent(), intent.getPriorityIntent().name()));
        metrics.put(
                "acc_mainAction",
                hit(expected.mainAction(), intent.getMainAction().name()));
        metrics.put("acc_statusIntents", statusHit(expected.statusIntents(), intent.getStatusIntents()));
        double sum = 0;
        for (String key : List.of(
                "acc_targetType", "acc_timeIntent", "acc_priorityIntent", "acc_mainAction", "acc_statusIntents")) {
            sum += metrics.get(key);
        }
        metrics.put("fieldAccuracy", sum / 5);
        if (!expected.expectedTopicKeywords().isEmpty()) {
            metrics.put("topicRecall", topicRecall(expected.expectedTopicKeywords(), intent));
        }
        return metrics;
    }

    private static double hit(String expected, String actual) {
        return expected.toUpperCase(Locale.ROOT).equals(actual) ? 1.0 : 0.0;
    }

    private static double statusHit(List<String> expected, List<TaskStatus> actual) {
        Set<String> expectedSet = new TreeSet<>();
        for (String status : expected) {
            expectedSet.add(status.toUpperCase(Locale.ROOT));
        }
        Set<String> actualSet = new TreeSet<>();
        for (TaskStatus status : actual) {
            actualSet.add(status.name());
        }
        return expectedSet.equals(actualSet) ? 1.0 : 0.0;
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

    private static Map<String, Object> output(SearchIntent intent) {
        Map<String, Object> output = new LinkedHashMap<>();
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
}
