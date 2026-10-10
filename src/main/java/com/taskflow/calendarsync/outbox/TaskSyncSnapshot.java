package com.taskflow.calendarsync.outbox;

import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskSyncState;

public class TaskSyncSnapshot {

    private final Task task;
    private final TaskSyncState syncState;
    private final OutboxStatus latestOutboxStatus;
    private final OutboxOpType latestOutboxOpType;
    private final String latestOutboxError;

    private TaskSyncSnapshot(
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

    public static TaskSyncSnapshot of(
            Task task,
            TaskSyncState syncState,
            OutboxStatus latestOutboxStatus,
            OutboxOpType latestOutboxOpType,
            String latestOutboxError) {
        return new TaskSyncSnapshot(task, syncState, latestOutboxStatus, latestOutboxOpType, latestOutboxError);
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
