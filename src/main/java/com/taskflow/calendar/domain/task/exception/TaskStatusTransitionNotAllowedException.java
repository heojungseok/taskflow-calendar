package com.taskflow.calendar.domain.task.exception;

import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;

public class TaskStatusTransitionNotAllowedException extends BusinessException {

    public TaskStatusTransitionNotAllowedException(TaskStatus from, TaskStatus to) {
        super(ErrorCode.TASK_STATUS_TRANSITION_NOT_ALLOWED,
                String.format("Cannot transition from %s to %s", from, to));
    }
}