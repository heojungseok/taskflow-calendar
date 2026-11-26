package com.taskflow.assistant.llm.bakeoff;

import java.util.List;

final class Stats {

    private Stats() {}

    static long percentile(List<Long> values, double fraction) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(fraction * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
