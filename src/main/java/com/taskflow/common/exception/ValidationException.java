package com.taskflow.common.exception;

import com.taskflow.common.ErrorCode;

public class ValidationException extends BusinessException {

    // 1. String 메시지만 받는 생성자 (HealthCheck 또는 테스트용)
    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
    }

    // 2. ErrorCode만 받는 생성자 (기본 메시지 사용)
    public ValidationException(ErrorCode errorCode) {
        super(errorCode, errorCode.getMessage());
    }

    // 3. ErrorCode + 커스텀 메시지 (상세한 정보 추가 시)
    public ValidationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}