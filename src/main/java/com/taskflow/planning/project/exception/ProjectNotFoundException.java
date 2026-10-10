package com.taskflow.planning.project.exception;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.ResourceNotFoundException;

public class ProjectNotFoundException extends ResourceNotFoundException {
    public ProjectNotFoundException(Long projectId) {
        super(ErrorCode.PROJECT_NOT_FOUND, "Project not found with id: " + projectId);
    }
}
