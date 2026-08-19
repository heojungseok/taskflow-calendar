package com.taskflow.calendar.domain.project.dto;

import lombok.Getter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Getter
public class CreateProjectRequest {

    @NotBlank(message = "프로젝트 이름은 필수입니다.")
    @Size(max = 100, message = "프로젝트 이름은 100자를 초과할 수 없습니다")
    private String name;
}
