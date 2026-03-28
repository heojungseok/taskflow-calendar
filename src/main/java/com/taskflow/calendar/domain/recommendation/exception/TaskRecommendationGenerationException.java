package com.taskflow.calendar.domain.recommendation.exception;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;

public class TaskRecommendationGenerationException extends BusinessException {

    public TaskRecommendationGenerationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
