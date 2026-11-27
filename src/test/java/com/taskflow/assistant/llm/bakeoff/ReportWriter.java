package com.taskflow.assistant.llm.bakeoff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.stream.DoubleStream;

final class ReportWriter {

    private static final int MIN_SAMPLES_FOR_P95 = 20;
    private static final String BASELINE_ID = "baseline(상수 기본값·seed 순서, LLM 없음)";

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
        md.append("\n지연은 성공한 호출 중 cold start를 뺀 값이다. 표본이 ")
                .append(MIN_SAMPLES_FOR_P95)
                .append("개 미만이면 p95 대신 p50과 max만 본다. 품질 지표는 성공한 호출의 평균이다.\n");
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
        headers.addAll(List.of("p50 ms", "p95 ms", "max ms", "지연 표본 수", "cold ms", "평균 output tokens", "실패 코드"));
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
        if (!runner.baselineMetrics().isEmpty()) {
            rows.add(baselineRow(runner));
        }
        return table(headers, rows);
    }

    private static List<String> baselineRow(FeatureRunner runner) {
        List<Map<String, Double>> metrics = runner.baselineMetrics();
        List<String> row = new ArrayList<>(List.of(BASELINE_ID, String.valueOf(metrics.size()), "100.0%"));
        for (FeatureRunner.MetricColumn column : runner.columns()) {
            row.add(metric(metrics, column));
        }
        row.addAll(List.of("-", "-", "-", "-", "-", "-", "-"));
        return row;
    }

    private static List<String> row(FeatureRunner runner, String candidateId, List<CallResult> mine) {
        List<CallResult> ok = mine.stream().filter(CallResult::ok).toList();
        List<String> row = new ArrayList<>(
                List.of(candidateId, String.valueOf(mine.size()), percent((double) ok.size() / mine.size())));
        List<Map<String, Double>> metrics = ok.stream().map(CallResult::metrics).toList();
        for (FeatureRunner.MetricColumn column : runner.columns()) {
            row.add(metric(metrics, column));
        }
        List<Long> warm =
                ok.stream().filter(r -> !r.cold()).map(CallResult::latencyMs).toList();
        row.add(ms(Stats.percentile(warm, 0.5)));
        row.add(warm.size() >= MIN_SAMPLES_FOR_P95 ? ms(Stats.percentile(warm, 0.95)) : "-");
        row.add(ms(Stats.percentile(warm, 1.0)));
        row.add(String.valueOf(warm.size()));
        row.add(mine.stream()
                .filter(CallResult::cold)
                .findFirst()
                .map(r -> String.valueOf(r.latencyMs()))
                .orElse("-"));
        row.add(average(
                ok.stream()
                        .map(CallResult::outputTokens)
                        .filter(Objects::nonNull)
                        .mapToDouble(Integer::doubleValue),
                false,
                "%.0f"));
        row.add(failures(mine));
        return row;
    }

    private static String metric(List<Map<String, Double>> metrics, FeatureRunner.MetricColumn column) {
        DoubleStream values = metrics.stream()
                .map(m -> m.get(column.key()))
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue);
        return average(values, column.percent(), "%.2f");
    }

    private static String average(DoubleStream values, boolean percent, String format) {
        OptionalDouble mean = values.average();
        if (mean.isEmpty()) {
            return "-";
        }
        return percent ? percent(mean.getAsDouble()) : format.formatted(mean.getAsDouble());
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
