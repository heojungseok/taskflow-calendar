package com.taskflow.assistant.llm.bakeoff;

import java.util.List;
import java.util.Map;

interface FeatureRunner {

    String feature();

    List<String> caseIds();

    List<MetricColumn> columns();

    Outcome run(int caseIndex, Generators generators);

    default List<Map<String, Double>> baselineMetrics() {
        return List.of();
    }

    record Outcome(Map<String, Object> output, Map<String, Double> metrics) {}

    record MetricColumn(String key, String header, boolean percent) {}
}
