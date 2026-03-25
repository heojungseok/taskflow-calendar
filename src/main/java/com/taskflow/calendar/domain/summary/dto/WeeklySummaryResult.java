package com.taskflow.calendar.domain.summary.dto;

import java.util.Collections;
import java.util.List;

public class WeeklySummaryResult {

    private final String summary;
    private final List<String> highlights;
    private final List<String> risks;
    private final List<String> nextActions;
    private final String model;

    private WeeklySummaryResult(String summary,
                                List<String> highlights,
                                List<String> risks,
                                List<String> nextActions,
                                String model) {
        this.summary = summary;
        this.highlights = highlights != null ? List.copyOf(highlights) : Collections.emptyList();
        this.risks = risks != null ? List.copyOf(risks) : Collections.emptyList();
        this.nextActions = nextActions != null ? List.copyOf(nextActions) : Collections.emptyList();
        this.model = model;
    }

    public static WeeklySummaryResult of(String summary,
                                         List<String> highlights,
                                         List<String> risks,
                                         List<String> nextActions,
                                         String model) {
        return new WeeklySummaryResult(summary, highlights, risks, nextActions, model);
    }

    public static WeeklySummaryResult empty() {
        return new WeeklySummaryResult(
                "이번 주에 요약할 Task가 없습니다.",
                Collections.emptyList(),
                Collections.emptyList(),
                List.of("새 Task를 추가하거나 마감일을 설정해보세요."),
                "local-empty-state"
        );
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getHighlights() {
        return highlights;
    }

    public List<String> getRisks() {
        return risks;
    }

    public List<String> getNextActions() {
        return nextActions;
    }

    public String getModel() {
        return model;
    }
}
