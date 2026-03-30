package com.taskflow.calendar.domain.search.dto;

import lombok.Getter;

@Getter
public class RelatedProjectSearchResultResponse {

    private final Long projectId;
    private final String projectName;
    private final int matchedTaskCount;
    private final int score;

    private RelatedProjectSearchResultResponse(Long projectId, String projectName, int matchedTaskCount, int score) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.matchedTaskCount = matchedTaskCount;
        this.score = score;
    }

    public static RelatedProjectSearchResultResponse of(Long projectId, String projectName, int matchedTaskCount, int score) {
        return new RelatedProjectSearchResultResponse(projectId, projectName, matchedTaskCount, score);
    }
}
