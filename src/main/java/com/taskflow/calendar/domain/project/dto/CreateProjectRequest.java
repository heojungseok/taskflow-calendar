package com.taskflow.calendar.domain.project.dto;

import lombok.Getter;

import jakarta.validation.constraints.NotBlank;

@Getter
public class CreateProjectRequest {

    @NotBlank(message = "프로젝트 이름은 필수입니다.")
    private String name;
}
