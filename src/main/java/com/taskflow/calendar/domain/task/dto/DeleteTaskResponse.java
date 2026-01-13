package com.taskflow.calendar.domain.task.dto;

import lombok.Getter;

/**
 * Task 삭제 응답 DTO
 */
@Getter
public class DeleteTaskResponse {

    private final Long deletedTaskId;

    private DeleteTaskResponse(Long deletedTaskId) {
        this.deletedTaskId = deletedTaskId;
    }

    public static DeleteTaskResponse of(Long taskId) {
        return new DeleteTaskResponse(taskId);
    }
}