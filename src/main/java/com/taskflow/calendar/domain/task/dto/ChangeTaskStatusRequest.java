package com.taskflow.calendar.domain.task.dto;

import com.taskflow.calendar.domain.task.TaskStatus;
import lombok.Getter;

import javax.validation.constraints.NotNull;

/**
 * Task 상태 변경 요청 DTO
 */
@Getter
public class ChangeTaskStatusRequest {

    @NotNull(message = "변경할 상태는 필수입니다")
    private TaskStatus toStatus;

    // JWT 적용 전 임시
//    @NotNull(message = "요청자 ID는 필수입니다")
//    private Long requestedByUserId;

    // 기본 생성자
    protected ChangeTaskStatusRequest() {}

    // 테스트용 생성자
    public ChangeTaskStatusRequest(TaskStatus toStatus) {
        this.toStatus = toStatus;
//        this.requestedByUserId = requestedByUserId;
    }
}