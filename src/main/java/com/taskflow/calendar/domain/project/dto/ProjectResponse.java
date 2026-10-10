package com.taskflow.calendar.domain.project.dto;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.common.TimeZones;
import java.time.Instant;
import lombok.Getter;

@Getter
public class ProjectResponse {

    private final Long id;
    private final String name;
    private final Instant createdAt;
    private final Instant updatedAt;

    private ProjectResponse(Long id, String name, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                TimeZones.toUtcInstant(project.getCreatedAt()),
                TimeZones.toUtcInstant(project.getUpdatedAt()));
    }
}
