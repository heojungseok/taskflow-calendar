package com.taskflow.calendar.domain.search.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class ProjectTaskSearchResponse {

    private final String query;
    private final boolean fallback;
    private final SearchIntentResponse intent;
    private final List<TaskSearchResultItemResponse> taskResults;
    private final List<RelatedProjectSearchResultResponse> relatedProjects;
    private final List<String> suggestedQueries;

    private ProjectTaskSearchResponse(String query,
                                      boolean fallback,
                                      SearchIntentResponse intent,
                                      List<TaskSearchResultItemResponse> taskResults,
                                      List<RelatedProjectSearchResultResponse> relatedProjects,
                                      List<String> suggestedQueries) {
        this.query = query;
        this.fallback = fallback;
        this.intent = intent;
        this.taskResults = taskResults;
        this.relatedProjects = relatedProjects;
        this.suggestedQueries = suggestedQueries;
    }

    public static ProjectTaskSearchResponse of(String query,
                                               boolean fallback,
                                               SearchIntentResponse intent,
                                               List<TaskSearchResultItemResponse> taskResults,
                                               List<RelatedProjectSearchResultResponse> relatedProjects,
                                               List<String> suggestedQueries) {
        return new ProjectTaskSearchResponse(
                query,
                fallback,
                intent,
                List.copyOf(taskResults),
                List.copyOf(relatedProjects),
                List.copyOf(suggestedQueries)
        );
    }
}
