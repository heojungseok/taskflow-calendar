package com.taskflow.calendar.domain.search.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TaskSearchRequest {

    @NotBlank(message = "검색어를 입력해주세요.")
    @Size(max = 500, message = "검색어는 500자를 초과할 수 없습니다.")
    private String query;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }
}
