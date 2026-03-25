package com.taskflow.calendar.domain.summary;

public enum TaskSyncState {
    SYNCED("현재 Task 상태가 Google Calendar에 반영된 상태"),
    PENDING_SYNC("Google Calendar 반영 대기 중이거나 재동기화가 필요한 상태"),
    FAILED_SYNC("Google Calendar 동기화 실패로 반영 여부가 불확실한 상태"),
    SYNC_DISABLED("Google Calendar 동기화를 사용하지 않는 상태"),
    DELETE_PENDING("Google Calendar에서 제거하는 작업이 아직 반영되지 않은 상태"),
    DELETE_FAILED("Google Calendar에서 제거하는 작업이 실패해 반영 여부가 불확실한 상태");

    private final String description;

    TaskSyncState(String description) {
        this.description = description;
    }

    public boolean isSynced() {
        return this == SYNCED;
    }

    public String getDescription() {
        return description;
    }
}
