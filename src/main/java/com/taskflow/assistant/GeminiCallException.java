package com.taskflow.assistant;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;
import java.util.List;

/** Gemini 호출 실패. statusCode가 0이면 응답을 받기 전에 실패했다(타임아웃, 연결 실패, 인터럽트). */
public class GeminiCallException extends BusinessException {

    private final int statusCode;
    private final long latencyMs;
    private final String classificationSource;
    private final String retryAfter;
    private final String upstreamStatus;
    private final List<String> reasonHints;

    public GeminiCallException(
            ErrorCode errorCode,
            String message,
            int statusCode,
            long latencyMs,
            String classificationSource,
            String retryAfter,
            String upstreamStatus,
            List<String> reasonHints) {
        super(errorCode, message);
        this.statusCode = statusCode;
        this.latencyMs = latencyMs;
        this.classificationSource = classificationSource;
        this.retryAfter = retryAfter;
        this.upstreamStatus = upstreamStatus;
        this.reasonHints = List.copyOf(reasonHints);
    }

    /** 다시 시도하거나 저장된 결과로 대신할 만한 실패인가. 설정 오류(4xx)는 아니다. */
    public boolean isTemporary() {
        return statusCode == 0 || statusCode == 429 || statusCode >= 500;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public long getLatencyMs() {
        return latencyMs;
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

    public List<String> getReasonHints() {
        return reasonHints;
    }
}
