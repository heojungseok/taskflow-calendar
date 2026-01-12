package com.taskflow.calendar.domain.project.dto;

import com.taskflow.calendar.domain.project.Project;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ProjectResponse {

    private final Long id;
    private final String name;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private ProjectResponse(Long id, String name, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getName(), project.getCreatedAt(), project.getUpdatedAt());
    }
}
