package com.taskflow.calendar.domain.search.dto;

import com.taskflow.calendar.domain.search.SemanticSearchStatus;
import lombok.Getter;

import java.util.List;

@Getter
public class ProjectTaskSearchResponse {

    private final String query;
    /** 의도 파싱이 약해 결과 없이 추천 질의만 돌려줬는가. 의미 검색 가용성과는 무관하다. */
    private final boolean intentFallback;
    /**
     * 이 응답을 만들 때 의미 검색이 어떤 상태였는가.
     * UNAVAILABLE이면 어휘 검색만으로 만든 결과다.
     * intentFallback=true인 응답에서는 검색을 아예 돌리지 않았으므로 현재 상태값일 뿐이다.
     */
    private final SemanticSearchStatus semanticStatus;
    private final SearchIntentResponse intent;
    private final List<TaskSearchResultItemResponse> taskResults;
    private final List<RelatedProjectSearchResultResponse> relatedProjects;
    private final List<String> suggestedQueries;

    private ProjectTaskSearchResponse(String query,
                                      boolean intentFallback,
                                      SemanticSearchStatus semanticStatus,
                                      SearchIntentResponse intent,
                                      List<TaskSearchResultItemResponse> taskResults,
                                      List<RelatedProjectSearchResultResponse> relatedProjects,
                                      List<String> suggestedQueries) {
        this.query = query;
        this.intentFallback = intentFallback;
        this.semanticStatus = semanticStatus;
        this.intent = intent;
        this.taskResults = taskResults;
        this.relatedProjects = relatedProjects;
        this.suggestedQueries = suggestedQueries;
    }

    public static ProjectTaskSearchResponse of(String query,
                                               boolean intentFallback,
                                               SemanticSearchStatus semanticStatus,
                                               SearchIntentResponse intent,
                                               List<TaskSearchResultItemResponse> taskResults,
                                               List<RelatedProjectSearchResultResponse> relatedProjects,
                                               List<String> suggestedQueries) {
        return new ProjectTaskSearchResponse(
                query,
                intentFallback,
                semanticStatus,
                intent,
                List.copyOf(taskResults),
                List.copyOf(relatedProjects),
                List.copyOf(suggestedQueries)
        );
    }
}
