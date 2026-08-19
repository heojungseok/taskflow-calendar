package com.taskflow.common;

import com.taskflow.calendar.domain.project.exception.ProjectNotFoundException;
import com.taskflow.calendar.domain.user.exception.UserNotFoundException;
import com.taskflow.common.exception.BusinessException;
import com.taskflow.common.exception.ResourceNotFoundException;
import com.taskflow.common.exception.UnauthorizedException;
import com.taskflow.common.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException e) {
        log.warn("Resource not found: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(ValidationException e) {
        log.warn("Validation error: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("Business error: {} - {}", e.getErrorCode().getCode(), e.getMessage());

        HttpStatus status = determineHttpStatus(e.getErrorCode());

        return ResponseEntity
                .status(status)
                .body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        log.warn("Validation failed: {}", message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, message));
    }

    /**
     * 잘못된 enum·숫자 파라미터. Spring 6.1의 TypeMismatchException은 아직 ErrorResponse가
     * 아니라서(6.2부터) 아래 분기에 걸리지 않는다. 명시하지 않으면 500으로 나간다.
     */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(TypeMismatchException e) {
        log.warn("Type mismatch: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.REQUEST_ERROR, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception e) {
        // 스프링 MVC 자체 예외(405, 404, 415, 잘못된 body, 타입 불일치...)는 ErrorResponse를 구현하고
        // 각자 올바른 상태 코드를 들고 있다. 여기서 걸러내지 않으면 전부 500으로 나간다.
        if (e instanceof ErrorResponse mvcError) {
            log.warn("Request error {}: {}", mvcError.getStatusCode(), e.getMessage());
            return ResponseEntity
                    .status(mvcError.getStatusCode())
                    .body(ApiResponse.error(ErrorCode.REQUEST_ERROR, e.getMessage()));
        }

        log.error("Unexpected error", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleUserNotFound(UserNotFoundException e) {

        log.warn("{}", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.USER_NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleProjectNotFound(ProjectNotFoundException e) {

        log.warn("{}", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.PROJECT_NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<?>> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED, e.getMessage()));
    }

    private HttpStatus determineHttpStatus(ErrorCode errorCode) {
        switch (errorCode) {
            case TASK_STATUS_TRANSITION_NOT_ALLOWED:
            case DEMO_RESOURCE_LIMIT:
                return HttpStatus.CONFLICT;
            case DEMO_MUTATION_LIMIT:
                return HttpStatus.TOO_MANY_REQUESTS;
            case VALIDATION_ERROR:
            case SCHEDULE_INVALID:
            case CALENDAR_SYNC_REQUIRES_DUE_AT:
                return HttpStatus.BAD_REQUEST;
            case LLM_RATE_LIMITED_TEMPORARY:
            case LLM_QUOTA_EXHAUSTED:
            case LLM_429_UNKNOWN:
                return HttpStatus.TOO_MANY_REQUESTS;
            case LLM_API_KEY_MISSING:
            case LLM_CONFIG_INVALID:
            case LLM_UPSTREAM_TEMPORARY_FAILURE:
                return HttpStatus.SERVICE_UNAVAILABLE;
            case LLM_INVALID_RESPONSE:
                return HttpStatus.BAD_GATEWAY;
            case WEEKLY_SUMMARY_FORCE_LIVE_DISABLED:
                return HttpStatus.FORBIDDEN;
            default:
                return HttpStatus.BAD_REQUEST;
        }
    }
}
