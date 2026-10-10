package com.taskflow.assistant.summary;

import com.taskflow.calendarsync.outbox.OutboxOpType;
import com.taskflow.calendarsync.outbox.OutboxStatus;
import com.taskflow.planning.task.Task;

public class SummaryTaskSnapshot {

    private final Task task;
    private final TaskSyncState syncState;
    private final OutboxStatus latestOutboxStatus;
    private final OutboxOpType latestOutboxOpType;
    private final String latestOutboxError;

    private SummaryTaskSnapshot(
            Task task,
            TaskSyncState syncState,
            OutboxStatus latestOutboxStatus,
            OutboxOpType latestOutboxOpType,
            String latestOutboxError) {
        this.task = task;
        this.syncState = syncState;
        this.latestOutboxStatus = latestOutboxStatus;
        this.latestOutboxOpType = latestOutboxOpType;
        this.latestOutboxError = latestOutboxError;
    }

    public static SummaryTaskSnapshot of(
            Task task,
            TaskSyncState syncState,
            OutboxStatus latestOutboxStatus,
            OutboxOpType latestOutboxOpType,
            String latestOutboxError) {
        return new SummaryTaskSnapshot(task, syncState, latestOutboxStatus, latestOutboxOpType, latestOutboxError);
    }

    public Task getTask() {
        return task;
    }

    public TaskSyncState getSyncState() {
        return syncState;
    }

    public OutboxStatus getLatestOutboxStatus() {
        return latestOutboxStatus;
    }

    public OutboxOpType getLatestOutboxOpType() {
        return latestOutboxOpType;
    }

    public String getLatestOutboxError() {
        return latestOutboxError;
    }
}
