package com.taskflow.calendar.domain.task.exception;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.ResourceNotFoundException;

public class TaskNotFoundException extends ResourceNotFoundException {

    public TaskNotFoundException(Long taskId) {
        super(ErrorCode.TASK_NOT_FOUND, "Task not found: " + taskId);
    }
}