package com.taskflow.planning.task.exception;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;
import com.taskflow.planning.task.TaskStatus;

public class TaskStatusTransitionNotAllowedException extends BusinessException {

    public TaskStatusTransitionNotAllowedException(TaskStatus from, TaskStatus to) {
        super(ErrorCode.TASK_STATUS_TRANSITION_NOT_ALLOWED, String.format("Cannot transition from %s to %s", from, to));
    }
}
