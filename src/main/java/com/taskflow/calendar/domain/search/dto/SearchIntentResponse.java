package com.taskflow.calendar.domain.search.dto;

import com.taskflow.calendar.domain.search.SearchIntent;
import com.taskflow.calendar.domain.task.TaskStatus;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
public class SearchIntentResponse {

    private final String rawQuery;
    private final String queryType;
    private final String targetType;
    private final String domainType;
    private final String mainAction;
    private final List<String> secondaryActions;
    private final List<String> topicTerms;
    private final List<String> participantTerms;
    private final List<String> locationTerms;
    private final String timeIntent;
    private final String priorityIntent;
    private final List<TaskStatus> statusIntents;
    private final String syncIntent;
    private final String relationPolicy;
    private final double overallConfidence;
    private final Map<String, Double> fieldConfidence;

    private SearchIntentResponse(String rawQuery,
                                 String queryType,
                                 String targetType,
                                 String domainType,
                                 String mainAction,
                                 List<String> secondaryActions,
                                 List<String> topicTerms,
                                 List<String> participantTerms,
                                 List<String> locationTerms,
                                 String timeIntent,
                                 String priorityIntent,
                                 List<TaskStatus> statusIntents,
                                 String syncIntent,
                                 String relationPolicy,
                                 double overallConfidence,
                                 Map<String, Double> fieldConfidence) {
        this.rawQuery = rawQuery;
        this.queryType = queryType;
        this.targetType = targetType;
        this.domainType = domainType;
        this.mainAction = mainAction;
        this.secondaryActions = secondaryActions;
        this.topicTerms = topicTerms;
        this.participantTerms = participantTerms;
        this.locationTerms = locationTerms;
        this.timeIntent = timeIntent;
        this.priorityIntent = priorityIntent;
        this.statusIntents = statusIntents;
        this.syncIntent = syncIntent;
        this.relationPolicy = relationPolicy;
        this.overallConfidence = overallConfidence;
        this.fieldConfidence = fieldConfidence;
    }

    public static SearchIntentResponse from(SearchIntent intent) {
        return new SearchIntentResponse(
                intent.getRawQuery(),
                intent.getQueryType().name(),
                intent.getTargetType().name(),
                intent.getDomainType().name(),
                intent.getMainAction().name(),
                intent.getSecondaryActions().stream().map(Enum::name).collect(Collectors.toList()),
                intent.getTopicTerms(),
                intent.getParticipantTerms(),
                intent.getLocationTerms(),
                intent.getTimeIntent().name(),
                intent.getPriorityIntent().name(),
                intent.getStatusIntents(),
                intent.getSyncIntent().name(),
                intent.getRelationPolicy().name(),
                intent.getOverallConfidence(),
                intent.getFieldConfidence()
        );
    }
}
