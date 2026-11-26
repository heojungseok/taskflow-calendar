package com.taskflow.assistant.llm.bakeoff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Stats {

    private Stats() {}

    static long percentile(List<Long> values, double fraction) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(fraction * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }
}
