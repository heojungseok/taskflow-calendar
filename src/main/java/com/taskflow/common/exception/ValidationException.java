package com.taskflow.common.exception;

import com.taskflow.common.ErrorCode;

public class ValidationException extends BusinessException{
    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
    }
}
