package com.taskflow.calendar.domain.search.dto;

import javax.validation.constraints.NotBlank;

public class TaskSearchRequest {

    @NotBlank(message = "검색어를 입력해주세요.")
    private String query;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }
}
