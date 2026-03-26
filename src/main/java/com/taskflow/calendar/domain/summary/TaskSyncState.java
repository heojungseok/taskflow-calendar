package com.taskflow.calendar.domain.summary;

public enum TaskSyncState {
    SYNCED,
    PENDING_SYNC,
    FAILED_SYNC,
    SYNC_DISABLED,
    DELETE_PENDING,
    DELETE_FAILED;

    public boolean isSynced() {
        return this == SYNCED;
    }
}
