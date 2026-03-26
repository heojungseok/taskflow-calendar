package com.taskflow.calendar.domain.summary;

import java.util.List;

public enum SummaryBucket {
    SYNCED(
            "캘린더에 반영된 일정의 진행 흐름과 일정상 위험에 집중하라.",
            "이번 주에 Google Calendar에 반영된 일정이 없습니다.",
            List.of("마감일이 있는 중요한 일정에 Google Calendar 동기화를 활성화해보세요.")
    ),
    UNSYNCED(
            "캘린더에 반영되지 않은 업무의 누락 위험과 반영 필요성을 분명하게 적고, 대기·실패·미사용 상태를 구분해서 설명하라.",
            "이번 주에 아직 Google Calendar에 반영되지 않은 일정은 없습니다.",
            List.of()
    );

    private final String promptFocus;
    private final String emptySummary;
    private final List<String> emptyNextActions;

    SummaryBucket(String promptFocus,
                  String emptySummary,
                  List<String> emptyNextActions) {
        this.promptFocus = promptFocus;
        this.emptySummary = emptySummary;
        this.emptyNextActions = emptyNextActions;
    }

    public String getPromptFocus() {
        return promptFocus;
    }

    public String getEmptySummary() {
        return emptySummary;
    }

    public List<String> getEmptyNextActions() {
        return emptyNextActions;
    }
}
