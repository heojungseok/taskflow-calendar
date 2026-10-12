package com.taskflow.assistant.summary.generator;

/** 주간 요약 요청 한 건의 크기와 입력 지문. 성공·실패 로그에 같이 남긴다. */
final class PromptMetrics {
    private final int requestBodyLength;
    private final String promptInputFingerprint;
    private final int syncedIncludedTaskCount;
    private final int unsyncedIncludedTaskCount;
    private final int syncedDescBriefChars;
    private final int unsyncedDescBriefChars;

    PromptMetrics(
            int requestBodyLength,
            String promptInputFingerprint,
            int syncedIncludedTaskCount,
            int unsyncedIncludedTaskCount,
            int syncedDescBriefChars,
            int unsyncedDescBriefChars) {
        this.requestBodyLength = requestBodyLength;
        this.promptInputFingerprint = promptInputFingerprint;
        this.syncedIncludedTaskCount = syncedIncludedTaskCount;
        this.unsyncedIncludedTaskCount = unsyncedIncludedTaskCount;
        this.syncedDescBriefChars = syncedDescBriefChars;
        this.unsyncedDescBriefChars = unsyncedDescBriefChars;
    }

    int getRequestBodyLength() {
        return requestBodyLength;
    }

    String getPromptInputFingerprint() {
        return promptInputFingerprint;
    }

    int getSyncedIncludedTaskCount() {
        return syncedIncludedTaskCount;
    }

    int getUnsyncedIncludedTaskCount() {
        return unsyncedIncludedTaskCount;
    }

    int getSyncedDescBriefChars() {
        return syncedDescBriefChars;
    }

    int getUnsyncedDescBriefChars() {
        return unsyncedDescBriefChars;
    }
}
