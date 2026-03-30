package com.taskflow.calendar.domain.search;

public class TaskSearchDocumentChangedEvent {

    private final Long taskId;

    public TaskSearchDocumentChangedEvent(Long taskId) {
        this.taskId = taskId;
    }

    public Long getTaskId() {
        return taskId;
    }
}
