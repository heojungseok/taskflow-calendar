package com.taskflow.calendar.domain.task.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;

import javax.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * Task 수정 요청 DTO
 * - 모든 필드가 Optional (null이면 수정하지 않음)
 */
@Getter
public class UpdateTaskRequest {

    @Size(max = 200, message = "제목은 200자를 초과할 수 없습니다")
    private String title;

    private String description;

    private Long assigneeUserId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime dueAt;

    private Boolean calendarSyncEnabled;

    // 기본 생성자
    protected UpdateTaskRequest() {}

    // 테스트용 생성자
    public UpdateTaskRequest(String title, String description, Long assigneeUserId,
                             LocalDateTime startAt, LocalDateTime dueAt,
                             Boolean calendarSyncEnabled) {
        this.title = title;
        this.description = description;
        this.assigneeUserId = assigneeUserId;
        this.startAt = startAt;
        this.dueAt = dueAt;
        this.calendarSyncEnabled = calendarSyncEnabled;
    }
}