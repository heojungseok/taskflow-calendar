package com.taskflow.calendar.domain.search;

import com.taskflow.calendar.domain.task.TaskStatus;

import java.util.List;
import java.util.Map;

public class SearchIntent {

    private final String rawQuery;
    private final SearchQueryType queryType;
    private final SearchTargetType targetType;
    private final SearchDomainType domainType;
    private final SearchActionIntent mainAction;
    private final List<SearchActionIntent> secondaryActions;
    private final List<String> topicTerms;
    private final List<String> participantTerms;
    private final List<String> locationTerms;
    private final boolean genericCompanionRequired;
    private final SearchTimeIntent timeIntent;
    private final SearchPriorityIntent priorityIntent;
    private final List<TaskStatus> statusIntents;
    private final SearchSyncIntent syncIntent;
    private final SearchRelationPolicy relationPolicy;
    private final double overallConfidence;
    private final Map<String, Double> fieldConfidence;
    private final List<String> suggestedQueries;

    private SearchIntent(String rawQuery,
                         SearchQueryType queryType,
                         SearchTargetType targetType,
                         SearchDomainType domainType,
                         SearchActionIntent mainAction,
                         List<SearchActionIntent> secondaryActions,
                         List<String> topicTerms,
                         List<String> participantTerms,
                         List<String> locationTerms,
                         boolean genericCompanionRequired,
                         SearchTimeIntent timeIntent,
                         SearchPriorityIntent priorityIntent,
                         List<TaskStatus> statusIntents,
                         SearchSyncIntent syncIntent,
                         SearchRelationPolicy relationPolicy,
                         double overallConfidence,
                         Map<String, Double> fieldConfidence,
                         List<String> suggestedQueries) {
        this.rawQuery = rawQuery;
        this.queryType = queryType;
        this.targetType = targetType;
        this.domainType = domainType;
        this.mainAction = mainAction;
        this.secondaryActions = List.copyOf(secondaryActions);
        this.topicTerms = List.copyOf(topicTerms);
        this.participantTerms = List.copyOf(participantTerms);
        this.locationTerms = List.copyOf(locationTerms);
        this.genericCompanionRequired = genericCompanionRequired;
        this.timeIntent = timeIntent;
        this.priorityIntent = priorityIntent;
        this.statusIntents = List.copyOf(statusIntents);
        this.syncIntent = syncIntent;
        this.relationPolicy = relationPolicy;
        this.overallConfidence = overallConfidence;
        this.fieldConfidence = Map.copyOf(fieldConfidence);
        this.suggestedQueries = List.copyOf(suggestedQueries);
    }

    public static SearchIntent of(String rawQuery,
                                  SearchQueryType queryType,
                                  SearchTargetType targetType,
                                  SearchDomainType domainType,
                                  SearchActionIntent mainAction,
                                  List<SearchActionIntent> secondaryActions,
                                  List<String> topicTerms,
                                  List<String> participantTerms,
                                  List<String> locationTerms,
                                  boolean genericCompanionRequired,
                                  SearchTimeIntent timeIntent,
                                  SearchPriorityIntent priorityIntent,
                                  List<TaskStatus> statusIntents,
                                  SearchSyncIntent syncIntent,
                                  SearchRelationPolicy relationPolicy,
                                  double overallConfidence,
                                  Map<String, Double> fieldConfidence,
                                  List<String> suggestedQueries) {
        return new SearchIntent(
                rawQuery,
                queryType,
                targetType,
                domainType,
                mainAction,
                secondaryActions,
                topicTerms,
                participantTerms,
                locationTerms,
                genericCompanionRequired,
                timeIntent,
                priorityIntent,
                statusIntents,
                syncIntent,
                relationPolicy,
                overallConfidence,
                fieldConfidence,
                suggestedQueries
        );
    }

    public static SearchIntent of(String rawQuery,
                                  SearchQueryType queryType,
                                  SearchTargetType targetType,
                                  SearchDomainType domainType,
                                  SearchActionIntent mainAction,
                                  List<SearchActionIntent> secondaryActions,
                                  List<String> topicTerms,
                                  List<String> participantTerms,
                                  List<String> locationTerms,
                                  SearchTimeIntent timeIntent,
                                  SearchPriorityIntent priorityIntent,
                                  List<TaskStatus> statusIntents,
                                  SearchSyncIntent syncIntent,
                                  SearchRelationPolicy relationPolicy,
                                  double overallConfidence,
                                  Map<String, Double> fieldConfidence,
                                  List<String> suggestedQueries) {
        return of(
                rawQuery,
                queryType,
                targetType,
                domainType,
                mainAction,
                secondaryActions,
                topicTerms,
                participantTerms,
                locationTerms,
                false,
                timeIntent,
                priorityIntent,
                statusIntents,
                syncIntent,
                relationPolicy,
                overallConfidence,
                fieldConfidence,
                suggestedQueries
        );
    }

    public String getRawQuery() {
        return rawQuery;
    }

    public SearchQueryType getQueryType() {
        return queryType;
    }

    public SearchTargetType getTargetType() {
        return targetType;
    }

    public SearchDomainType getDomainType() {
        return domainType;
    }

    public SearchActionIntent getMainAction() {
        return mainAction;
    }

    public List<SearchActionIntent> getSecondaryActions() {
        return secondaryActions;
    }

    public List<String> getTopicTerms() {
        return topicTerms;
    }

    public List<String> getParticipantTerms() {
        return participantTerms;
    }

    public List<String> getLocationTerms() {
        return locationTerms;
    }

    public boolean isGenericCompanionRequired() {
        return genericCompanionRequired;
    }

    public SearchTimeIntent getTimeIntent() {
        return timeIntent;
    }

    public SearchPriorityIntent getPriorityIntent() {
        return priorityIntent;
    }

    public List<TaskStatus> getStatusIntents() {
        return statusIntents;
    }

    public SearchSyncIntent getSyncIntent() {
        return syncIntent;
    }

    public SearchRelationPolicy getRelationPolicy() {
        return relationPolicy;
    }

    public double getOverallConfidence() {
        return overallConfidence;
    }

    public Map<String, Double> getFieldConfidence() {
        return fieldConfidence;
    }

    public List<String> getSuggestedQueries() {
        return suggestedQueries;
    }

    public boolean hasUsefulTopicTerms() {
        return !topicTerms.isEmpty();
    }

    public boolean hasUsefulMainAction() {
        return mainAction != SearchActionIntent.UNKNOWN;
    }

    public boolean hasUsefulSecondaryAction() {
        return secondaryActions.stream().anyMatch(intent -> intent != SearchActionIntent.UNKNOWN);
    }

    public boolean hasUsefulParticipantTerms() {
        return !participantTerms.isEmpty();
    }

    public boolean hasUsefulLocationTerms() {
        return !locationTerms.isEmpty();
    }

    public boolean hasUsefulStructuredSignal() {
        return hasUsefulTopicTerms()
                || hasUsefulMainAction()
                || hasUsefulParticipantTerms()
                || hasUsefulLocationTerms();
    }
}
