package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.assistant.llm.bakeoff.BakeoffFixtures.RecommendationCase;
import com.taskflow.assistant.recommendation.generator.TaskRecommendationGenerationResult;
import com.taskflow.assistant.recommendation.generator.TaskRecommendationItemResult;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RecommendationRunner implements FeatureRunner {

    private static final List<MetricColumn> COLUMNS = List.of(
            new MetricColumn("countMatch", "개수 일치", true),
            new MetricColumn("idsValid", "id ⊆ 후보", true),
            new MetricColumn("noDuplicates", "중복 없음", true),
            new MetricColumn("mustIncludeHit", "필수 포함", true),
            new MetricColumn("mustExcludeViolations", "제외 위반(건/호출)", false),
            new MetricColumn("koreanRatio", "reason 한글 비율", true));

    private final List<RecommendationCase> cases;

    RecommendationRunner(List<RecommendationCase> cases) {
        this.cases = cases;
    }

    @Override
    public String feature() {
        return "recommendation";
    }

    @Override
    public List<String> caseIds() {
        return cases.stream().map(RecommendationCase::id).toList();
    }

    @Override
    public List<MetricColumn> columns() {
        return COLUMNS;
    }

    @Override
    public Outcome run(int caseIndex, Generators generators) {
        RecommendationCase expected = cases.get(caseIndex);
        TaskRecommendationGenerationResult result = generators
                .recommendation()
                .generate(expected.project(), expected.candidates(), expected.count(), BakeoffFixtures.TODAY);
        return new Outcome(output(result), score(expected, result.getItems()));
    }

    private Map<String, Double> score(RecommendationCase expected, List<TaskRecommendationItemResult> items) {
        Set<Long> candidateIds = new HashSet<>();
        for (TaskSyncSnapshot candidate : expected.candidates()) {
            candidateIds.add(candidate.getTask().getId());
        }
        List<Long> returned = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (TaskRecommendationItemResult item : items) {
            returned.add(item.getTaskId());
            reasons.add(item.getReason());
        }
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("countMatch", items.size() == expected.count() ? 1.0 : 0.0);
        metrics.put("idsValid", candidateIds.containsAll(returned) ? 1.0 : 0.0);
        metrics.put("noDuplicates", new HashSet<>(returned).size() == returned.size() ? 1.0 : 0.0);
        metrics.put("mustIncludeHit", includeHit(expected.mustIncludeIds(), returned));
        metrics.put("mustExcludeViolations", (double) violations(expected.mustExcludeIds(), returned));
        metrics.put("koreanRatio", TextMetrics.koreanRatio(reasons));
        return metrics;
    }

    private static double includeHit(List<Long> mustInclude, List<Long> returned) {
        if (mustInclude.isEmpty()) {
            return 1.0;
        }
        int hits = 0;
        for (Long id : mustInclude) {
            if (returned.contains(id)) {
                hits++;
            }
        }
        return (double) hits / mustInclude.size();
    }

    private static long violations(List<Long> mustExclude, List<Long> returned) {
        return returned.stream().filter(mustExclude::contains).count();
    }

    private static Map<String, Object> output(TaskRecommendationGenerationResult result) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (TaskRecommendationItemResult item : result.getItems()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("taskId", item.getTaskId());
            entry.put("primaryTag", item.getPrimaryTag());
            entry.put("secondaryTag", item.getSecondaryTag());
            entry.put("reason", item.getReason());
            items.add(entry);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("items", items);
        return output;
    }
}
