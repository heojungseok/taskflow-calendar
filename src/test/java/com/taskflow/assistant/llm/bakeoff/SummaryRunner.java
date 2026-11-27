package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.assistant.llm.bakeoff.BakeoffFixtures.SummaryCase;
import com.taskflow.assistant.summary.dto.WeeklySummaryResult;
import com.taskflow.assistant.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SummaryRunner implements FeatureRunner {

    private static final String LOCAL_EMPTY_MODEL = "local-empty-state";
    private static final List<MetricColumn> COLUMNS = List.of(
            new MetricColumn("koreanRatio", "한글 비율(로컬 빈 섹션 문구 제외)", true),
            new MetricColumn("groundedness", "제목 어휘 겹침", true));

    private final List<SummaryCase> cases;

    SummaryRunner(List<SummaryCase> cases) {
        this.cases = cases;
    }

    @Override
    public String feature() {
        return "summary";
    }

    @Override
    public List<String> caseIds() {
        return cases.stream().map(SummaryCase::id).toList();
    }

    @Override
    public List<MetricColumn> columns() {
        return COLUMNS;
    }

    @Override
    public Outcome run(int caseIndex, Generators generators) {
        SummaryCase input = cases.get(caseIndex);
        WeeklySummarySectionsResult result = generators
                .summary()
                .generate(
                        input.project(),
                        input.synced(),
                        input.syncedTotal(),
                        input.unsynced(),
                        input.unsyncedTotal(),
                        input.weekStart(),
                        input.weekEnd());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("synced", section(result.getSynced()));
        output.put("unsynced", section(result.getUnsynced()));
        return new Outcome(output, score(input, result));
    }

    private Map<String, Double> score(SummaryCase input, WeeklySummarySectionsResult result) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        List<String> modelText = new ArrayList<>();
        List<String> claims = new ArrayList<>();
        for (WeeklySummaryResult section : List.of(result.getSynced(), result.getUnsynced())) {
            if (LOCAL_EMPTY_MODEL.equals(section.getModel())) {
                continue;
            }
            modelText.add(section.getSummary());
            modelText.addAll(claimsOf(section));
            claims.addAll(claimsOf(section));
        }
        if (!modelText.isEmpty()) {
            metrics.put("koreanRatio", TextMetrics.koreanRatio(modelText));
        }
        if (!claims.isEmpty()) {
            metrics.put("groundedness", groundedness(input, claims));
        }
        return metrics;
    }

    private static List<String> claimsOf(WeeklySummaryResult section) {
        List<String> claims = new ArrayList<>(section.getHighlights());
        claims.addAll(section.getRisks());
        claims.addAll(section.getNextActions());
        return claims;
    }

    private static double groundedness(SummaryCase input, List<String> claims) {
        List<String> titles = new ArrayList<>();
        for (TaskSyncSnapshot snapshot : input.synced()) {
            titles.add(snapshot.getTask().getTitle());
        }
        for (TaskSyncSnapshot snapshot : input.unsynced()) {
            titles.add(snapshot.getTask().getTitle());
        }
        Set<String> tokens = TextMetrics.distinctiveTokens(titles);
        int grounded = 0;
        for (String claim : claims) {
            if (TextMetrics.containsAny(claim, tokens)) {
                grounded++;
            }
        }
        return (double) grounded / claims.size();
    }

    private static Map<String, Object> section(WeeklySummaryResult section) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("summary", section.getSummary());
        map.put("highlights", section.getHighlights());
        map.put("risks", section.getRisks());
        map.put("nextActions", section.getNextActions());
        map.put("model", section.getModel());
        return map;
    }
}
