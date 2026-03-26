package com.taskflow.calendar.domain.summary;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;

public class WeeklySummaryGenerationException extends BusinessException {

    private final boolean fallbackEligible;

    public WeeklySummaryGenerationException(ErrorCode errorCode, String message, boolean fallbackEligible) {
        super(errorCode, message);
        this.fallbackEligible = fallbackEligible;
    }

    public boolean isFallbackEligible() {
        return fallbackEligible;
    }
}
