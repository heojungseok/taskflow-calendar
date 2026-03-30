package com.taskflow.calendar.domain.search;

public class TaskSearchDocumentDeletedEvent {

    private final Long taskId;

    public TaskSearchDocumentDeletedEvent(Long taskId) {
        this.taskId = taskId;
    }

    public Long getTaskId() {
        return taskId;
    }
}
