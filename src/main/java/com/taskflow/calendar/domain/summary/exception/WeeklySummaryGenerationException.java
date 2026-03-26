package com.taskflow.calendar.domain.summary.exception;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;

import java.util.List;

public class WeeklySummaryGenerationException extends BusinessException {

    private final boolean fallbackEligible;
    private final String classificationSource;
    private final String retryAfter;
    private final String upstreamStatus;
    private final List<String> upstreamReasonHints;

    public WeeklySummaryGenerationException(ErrorCode errorCode, String message, boolean fallbackEligible) {
        this(errorCode, message, fallbackEligible, null, null, null, List.of());
    }

    public WeeklySummaryGenerationException(ErrorCode errorCode,
                                            String message,
                                            boolean fallbackEligible,
                                            String classificationSource,
                                            String retryAfter,
                                            String upstreamStatus,
                                            List<String> upstreamReasonHints) {
        super(errorCode, message);
        this.fallbackEligible = fallbackEligible;
        this.classificationSource = classificationSource;
        this.retryAfter = retryAfter;
        this.upstreamStatus = upstreamStatus;
        this.upstreamReasonHints = upstreamReasonHints == null ? List.of() : List.copyOf(upstreamReasonHints);
    }

    public boolean isFallbackEligible() {
        return fallbackEligible;
    }

    public String getClassificationSource() {
        return classificationSource;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    public String getUpstreamStatus() {
        return upstreamStatus;
    }

    public List<String> getUpstreamReasonHints() {
        return upstreamReasonHints;
    }
}
