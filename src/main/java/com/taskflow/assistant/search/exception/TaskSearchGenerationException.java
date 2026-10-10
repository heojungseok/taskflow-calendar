package com.taskflow.assistant.search.exception;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;

public class TaskSearchGenerationException extends BusinessException {

    public TaskSearchGenerationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
