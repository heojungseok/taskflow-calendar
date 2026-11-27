package com.taskflow.assistant.search.bakeoff;

import com.taskflow.assistant.search.bakeoff.EmbeddingFixture.FixtureQuery;
import com.taskflow.assistant.search.bakeoff.RetrievalMetrics.QueryScore;
import com.taskflow.assistant.search.bakeoff.RetrievalMetrics.Summary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class BakeoffReport {

    private record Row(
            EmbeddingCandidate candidate,
            EmbeddingVectors.Timed timed,
            List<QueryScore> raw,
            List<QueryScore> production) {}

    private final EmbeddingFixture fixture;
    private final List<Row> rows = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();

    BakeoffReport(EmbeddingFixture fixture) {
        this.fixture = fixture;
    }

    void add(
            EmbeddingCandidate candidate,
            EmbeddingVectors.Timed timed,
            List<QueryScore> raw,
            List<QueryScore> production) {
        rows.add(new Row(candidate, timed, raw, production));
    }

    void skip(EmbeddingCandidate candidate, String reason) {
        skipped.add(candidate.name() + ": " + reason);
    }

    String markdown() {
        StringBuilder out = new StringBuilder("# 임베딩 bake-off\n\n");
        out.append("Task %d개, 독립 검색어 %d개, 변형 묶음 %d개\n\n"
                .formatted(
                        fixture.tasks().size(),
                        fixture.queries().stream()
                                .filter(query -> !query.inFamily())
                                .count(),
                        families()));
        overall(out);
        categories(out);
        broad(out);
        familyConsistency(out);
        paired(out);
        if (!skipped.isEmpty()) {
            out.append("\n## 건너뜀\n");
            skipped.forEach(line -> out.append("- ").append(line).append('\n'));
        }
        return out.toString();
    }

    private void overall(StringBuilder out) {
        out.append(
                "## 전체(독립 검색어, 넓은 검색어 제외)\n\n| 후보 | 질의 형식 | recall@5 | recall@10 | precision@5 | hit@1 | MRR | 오답 선두 | 문서 ms/건 | 질의 p50 ms | 질의 max ms |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            line(out, row, "원문", RetrievalMetrics.summarize(independent(row.raw())));
            line(out, row, "운영(topic)", RetrievalMetrics.summarize(independent(row.production())));
        }
    }

    private void line(StringBuilder out, Row row, String mode, Summary s) {
        List<Long> latencies = row.timed().queryMillis().stream().sorted().toList();
        String p50 = latencies.isEmpty() ? "캐시" : String.valueOf(latencies.get(latencies.size() / 2));
        String max = latencies.isEmpty() ? "캐시" : String.valueOf(latencies.get(latencies.size() - 1));
        String perDoc = row.timed().documentMillis() == 0
                ? "캐시"
                : "%.0f"
                        .formatted((double) row.timed().documentMillis()
                                / fixture.tasks().size());
        out.append("| %s | %s | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %s | %s | %s |\n"
                .formatted(
                        row.candidate().name(),
                        mode,
                        s.recallAt5(),
                        s.recallAt10(),
                        s.precisionAt5(),
                        s.hitAt1(),
                        s.mrr(),
                        s.hardNegativeFirstRate(),
                        perDoc,
                        p50,
                        max));
    }

    private void categories(StringBuilder out) {
        out.append("\n## 유형별 recall@5 / precision@5 (운영 질의 형식)\n\n| 후보 |");
        List<String> categories = EmbeddingFixture.CATEGORIES.stream()
                .filter(c -> !c.equals("paraphrase"))
                .toList();
        categories.forEach(c -> out.append(' ').append(c).append(" |"));
        out.append("\n|---|").append("---|".repeat(categories.size())).append('\n');
        for (Row row : rows) {
            out.append("| ").append(row.candidate().name()).append(" |");
            for (String category : categories) {
                List<QueryScore> scores = row.production().stream()
                        .filter(score -> !score.query().inFamily())
                        .filter(score -> score.query().category().equals(category))
                        .toList();
                Summary summary = RetrievalMetrics.summarize(scores);
                out.append(" %.2f / %.2f |".formatted(summary.recallAt5(), summary.precisionAt5()));
            }
            out.append('\n');
        }
    }

    private void broad(StringBuilder out) {
        out.append("\n## 넓은 검색어 (정답 5~25개, 운영 질의 형식)\n\n| 후보 | precision@5 | hit@1 | 오답 선두 |\n|---|---|---|---|\n");
        for (Row row : rows) {
            Summary s = RetrievalMetrics.summarize(row.production().stream()
                    .filter(score -> score.query().broad())
                    .toList());
            out.append("| %s | %.3f | %.3f | %.3f |\n"
                    .formatted(row.candidate().name(), s.precisionAt5(), s.hitAt1(), s.hardNegativeFirstRate()));
        }
    }

    private void familyConsistency(StringBuilder out) {
        out.append("\n## 변형 묶음 (운영 질의 형식)\n\n| 후보 | 묶음 hit@5 평균 | 모든 변형이 같은 결과인 묶음 비율 |\n|---|---|---|\n");
        for (Row row : rows) {
            Map<String, List<QueryScore>> byFamily = row.production().stream()
                    .filter(score -> score.query().inFamily())
                    .collect(Collectors.groupingBy(score -> score.query().family(), TreeMap::new, Collectors.toList()));
            double hit = 0;
            double consistent = 0;
            for (List<QueryScore> members : byFamily.values()) {
                long hits = members.stream().filter(score -> score.hitAt(5)).count();
                hit += (double) hits / members.size();
                consistent += hits == 0 || hits == members.size() ? 1 : 0;
            }
            int n = Math.max(1, byFamily.size());
            out.append("| %s | %.3f | %.3f |\n".formatted(row.candidate().name(), hit / n, consistent / n));
        }
    }

    private void paired(StringBuilder out) {
        if (rows.size() < 2) {
            return;
        }
        Row baseline = rows.get(0);
        out.append("\n## %s 대비 hit@5 (독립 검색어, 운영 질의 형식)\n\n| 후보 | 이김 | 짐 | 같음 |\n|---|---|---|---|\n"
                .formatted(baseline.candidate().name()));
        List<QueryScore> base = independent(baseline.production());
        for (Row row : rows.subList(1, rows.size())) {
            List<QueryScore> other = independent(row.production());
            int win = 0;
            int lose = 0;
            for (int i = 0; i < base.size(); i++) {
                boolean b = base.get(i).hitAt(5);
                boolean o = other.get(i).hitAt(5);
                win += o && !b ? 1 : 0;
                lose += b && !o ? 1 : 0;
            }
            out.append(
                    "| %s | %d | %d | %d |\n".formatted(row.candidate().name(), win, lose, base.size() - win - lose));
        }
    }

    private long families() {
        return fixture.queries().stream()
                .filter(FixtureQuery::inFamily)
                .map(FixtureQuery::family)
                .distinct()
                .count();
    }

    private static List<QueryScore> independent(List<QueryScore> scores) {
        return scores.stream()
                .filter(score -> !score.query().inFamily() && !score.query().broad())
                .toList();
    }
}
