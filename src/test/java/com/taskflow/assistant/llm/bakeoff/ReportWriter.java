package com.taskflow.assistant.llm.bakeoff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ReportWriter {

    private ReportWriter() {}

    static String render(
            List<String> header,
            List<String> candidateIds,
            List<FeatureRunner> runners,
            List<CallResult> results,
            List<ConcurrencyResult> concurrency) {
        StringBuilder md = new StringBuilder("# LLM bake-off report\n\n");
        for (String line : header) {
            md.append("- ").append(line).append('\n');
        }
        md.append("\n지연 p50/p95는 성공한 호출 중 cold start를 뺀 값이고, 품질 지표는 성공한 호출의 평균이다.\n");
        for (FeatureRunner runner : runners) {
            md.append("\n## ").append(runner.feature()).append("\n\n");
            md.append(featureTable(runner, candidateIds, results));
        }
        if (!concurrency.isEmpty()) {
            md.append("\n## concurrency (search 동시 요청)\n\n").append(concurrencyTable(concurrency));
        }
        return md.toString();
    }

    private static String featureTable(FeatureRunner runner, List<String> candidateIds, List<CallResult> results) {
        List<String> headers = new ArrayList<>(List.of("candidate", "호출", "성공률"));
        for (FeatureRunner.MetricColumn column : runner.columns()) {
            headers.add(column.header());
        }
        headers.addAll(List.of("p50 ms", "p95 ms", "cold ms", "평균 output tokens", "실패 코드"));
        List<List<String>> rows = new ArrayList<>();
        for (String candidateId : candidateIds) {
            List<CallResult> mine = results.stream()
                    .filter(r ->
                            r.candidateId().equals(candidateId) && r.feature().equals(runner.feature()))
                    .toList();
            if (!mine.isEmpty()) {
                rows.add(row(runner, candidateId, mine));
            }
        }
        return table(headers, rows);
    }

    private static List<String> row(FeatureRunner runner, String candidateId, List<CallResult> mine) {
        List<CallResult> ok = mine.stream().filter(CallResult::ok).toList();
        List<String> row = new ArrayList<>(
                List.of(candidateId, String.valueOf(mine.size()), percent((double) ok.size() / mine.size())));
        for (FeatureRunner.MetricColumn column : runner.columns()) {
            row.add(metric(ok, column));
        }
        List<Long> warm =
                ok.stream().filter(r -> !r.cold()).map(CallResult::latencyMs).toList();
        row.add(ms(Stats.percentile(warm, 0.5)));
        row.add(ms(Stats.percentile(warm, 0.95)));
        row.add(mine.stream()
                .filter(CallResult::cold)
                .findFirst()
                .map(r -> String.valueOf(r.latencyMs()))
                .orElse("-"));
        row.add(tokens(ok));
        row.add(failures(mine));
        return row;
    }

    private static String metric(List<CallResult> ok, FeatureRunner.MetricColumn column) {
        List<Double> values = new ArrayList<>();
        for (CallResult result : ok) {
            Double value = result.metrics().get(column.key());
            if (value != null) {
                values.add(value);
            }
        }
        double mean = Stats.mean(values);
        if (Double.isNaN(mean)) {
            return "-";
        }
        return column.percent() ? percent(mean) : "%.2f".formatted(mean);
    }

    private static String tokens(List<CallResult> ok) {
        List<Double> values = new ArrayList<>();
        for (CallResult result : ok) {
            if (result.outputTokens() != null) {
                values.add(result.outputTokens().doubleValue());
            }
        }
        double mean = Stats.mean(values);
        return Double.isNaN(mean) ? "-" : "%.0f".formatted(mean);
    }

    private static String failures(List<CallResult> mine) {
        Map<String, Integer> counts = new TreeMap<>();
        for (CallResult result : mine) {
            if (!result.ok()) {
                counts.merge(result.errorCode(), 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((code, count) -> parts.add(code + " x" + count));
        return String.join(", ", parts);
    }

    private static String concurrencyTable(List<ConcurrencyResult> concurrency) {
        List<String> headers = List.of("candidate", "동시 요청", "성공", "wall ms", "요청별 지연 ms", "실패 코드");
        List<List<String>> rows = new ArrayList<>();
        for (ConcurrencyResult result : concurrency) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            result.errors().forEach(code -> counts.merge(code, 1, Integer::sum));
            rows.add(List.of(
                    result.candidateId(),
                    String.valueOf(result.requests()),
                    String.valueOf(result.successes()),
                    String.valueOf(result.wallMs()),
                    result.latenciesMs().stream().sorted().toList().toString(),
                    counts.isEmpty() ? "-" : counts.toString()));
        }
        return table(headers, rows);
    }

    private static String table(List<String> headers, List<List<String>> rows) {
        StringBuilder md = new StringBuilder();
        md.append("| ").append(String.join(" | ", headers)).append(" |\n");
        md.append("|").append("---|".repeat(headers.size())).append('\n');
        for (List<String> row : rows) {
            md.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return md.toString();
    }

    private static String percent(double fraction) {
        return "%.1f%%".formatted(fraction * 100);
    }

    private static String ms(long value) {
        return value < 0 ? "-" : String.valueOf(value);
    }
}
