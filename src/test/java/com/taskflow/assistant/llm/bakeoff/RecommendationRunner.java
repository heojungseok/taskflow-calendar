package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.assistant.llm.LlmJsonReader;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class RecommendationRunner implements FeatureRunner {

    private static final List<MetricColumn> COLUMNS = List.of(
            new MetricColumn("rawIdsOutside", "후보 밖 id(건/호출, 원문)", false),
            new MetricColumn("rawDuplicates", "중복 id(건/호출, 원문)", false),
            new MetricColumn("rawCountMatch", "요청 개수 일치(원문)", true),
            new MetricColumn("countMatch", "개수 일치 (운영 정규화 후)", true),
            new MetricColumn("mustIncludeHit", "필수 포함 (운영 정규화 후)", true),
            new MetricColumn("mustTop1", "필수 작업 top-1 (운영 정규화 후)", true),
            new MetricColumn("mustFirstRank", "필수 작업 첫 순위(낮을수록 좋음, 미포함=count+1)", false),
            new MetricColumn("mustExcludeViolations", "제외 위반(건/호출)", false),
            new MetricColumn("koreanRatio", "reason 한글 비율", true));

    private final List<RecommendationCase> cases;
    private final LlmJsonReader jsonReader;

    RecommendationRunner(List<RecommendationCase> cases, ObjectMapper objectMapper) {
        this.cases = cases;
        this.jsonReader = new LlmJsonReader(objectMapper);
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
    public List<Map<String, Double>> baselineMetrics() {
        return cases.stream()
                .map(expected -> score(
                        expected,
                        expected.candidates().stream()
                                .limit(expected.count())
                                .map(candidate -> candidate.getTask().getId())
                                .toList(),
                        List.of()))
                .toList();
    }

    @Override
    public Outcome run(int caseIndex, Generators generators) {
        RecommendationCase expected = cases.get(caseIndex);
        TaskRecommendationGenerationResult result = generators
                .recommendation()
                .generate(expected.project(), expected.candidates(), expected.count(), BakeoffFixtures.TODAY);
        String rawText = MeteredLlmProvider.lastText();
        List<Long> returned = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (TaskRecommendationItemResult item : result.getItems()) {
            returned.add(item.getTaskId());
            reasons.add(item.getReason());
        }
        Map<String, Double> metrics = score(expected, returned, reasons);
        metrics.putAll(rawMetrics(expected, rawText));
        return new Outcome(output(result, rawText), metrics);
    }

    private Map<String, Double> rawMetrics(RecommendationCase expected, String rawText) {
        Set<Long> candidateIds = new HashSet<>();
        for (TaskSyncSnapshot candidate : expected.candidates()) {
            candidateIds.add(candidate.getTask().getId());
        }
        JsonNode items = jsonReader.read(rawText).path("items");
        Set<Long> seen = new HashSet<>();
        int outside = 0;
        int duplicates = 0;
        for (JsonNode item : items) {
            long id = item.path("taskId").asLong(-1);
            if (!candidateIds.contains(id)) {
                outside++;
            } else if (!seen.add(id)) {
                duplicates++;
            }
        }
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("rawIdsOutside", (double) outside);
        metrics.put("rawDuplicates", (double) duplicates);
        metrics.put("rawCountMatch", items.size() == expected.count() ? 1.0 : 0.0);
        return metrics;
    }

    private Map<String, Double> score(RecommendationCase expected, List<Long> returned, List<String> reasons) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("countMatch", returned.size() == expected.count() ? 1.0 : 0.0);
        metrics.put("mustIncludeHit", includeHit(expected.mustIncludeIds(), returned));
        boolean top1 = !returned.isEmpty() && expected.mustIncludeIds().contains(returned.get(0));
        metrics.put("mustTop1", top1 ? 1.0 : 0.0);
        metrics.put("mustFirstRank", firstRank(expected, returned));
        metrics.put("mustExcludeViolations", (double)
                returned.stream().filter(expected.mustExcludeIds()::contains).count());
        if (!reasons.isEmpty()) {
            metrics.put("koreanRatio", TextMetrics.koreanRatio(reasons));
        }
        return metrics;
    }

    private static double includeHit(List<Long> mustInclude, List<Long> returned) {
        long hits = mustInclude.stream().filter(returned::contains).count();
        return (double) hits / mustInclude.size();
    }

    private static double firstRank(RecommendationCase expected, List<Long> returned) {
        for (int i = 0; i < returned.size(); i++) {
            if (expected.mustIncludeIds().contains(returned.get(i))) {
                return i + 1;
            }
        }
        return expected.count() + 1;
    }

    private static Map<String, Object> output(TaskRecommendationGenerationResult result, String rawText) {
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
        output.put("rawText", rawText);
        output.put("items", items);
        return output;
    }
}
