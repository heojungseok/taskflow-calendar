package com.taskflow.assistant.search;

import static com.taskflow.assistant.search.MatchScore.ROLE_ENTITY_WEIGHT;
import static com.taskflow.assistant.search.SearchBoostScorer.domainScore;
import static com.taskflow.assistant.search.SearchBoostScorer.priorityScore;
import static com.taskflow.assistant.search.SearchBoostScorer.semanticScore;
import static com.taskflow.assistant.search.SearchBoostScorer.statusScore;
import static com.taskflow.assistant.search.SearchBoostScorer.syncScore;
import static com.taskflow.assistant.search.SearchBoostScorer.timeScore;
import static com.taskflow.assistant.search.SearchTermMatcher.actionScore;
import static com.taskflow.assistant.search.SearchTermMatcher.companionScore;
import static com.taskflow.assistant.search.SearchTermMatcher.secondaryActionScore;
import static com.taskflow.assistant.search.SearchTermMatcher.termScore;
import static com.taskflow.assistant.search.SearchTermMatcher.topicScore;
import static com.taskflow.assistant.search.SearchText.normalizeText;

import com.taskflow.assistant.search.SearchBoostScorer.TimeScore;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.planning.task.Task;
import java.time.LocalDateTime;
import java.util.OptionalInt;

/** Task 하나의 검색 점수를 매긴다. 필수 조건을 못 넘거나 점수가 없으면 비어 있다. */
final class TaskSearchScorer {

    private static final int RELATION_WEIGHT = 20;

    private TaskSearchScorer() {}

    static OptionalInt score(
            TaskSyncSnapshot snapshot, SearchIntent intent, double semanticSimilarity, LocalDateTime now) {
        Task task = snapshot.getTask();
        String text = normalizeText(task.getProject().getName(), task.getTitle(), task.getDescription());

        MatchScore topicScore = topicScore(intent.getTopicTerms(), text);
        MatchScore participantScore = termScore(intent.getParticipantTerms(), text);
        MatchScore locationScore = termScore(intent.getLocationTerms(), text);
        MatchScore companionScore = companionScore(intent, text);
        MatchScore roleEntityScore = roleEntityScore(topicScore, participantScore, locationScore, companionScore);
        MatchScore mainActionScore = actionScore(intent.getMainAction(), text);
        MatchScore secondaryActionScore = secondaryActionScore(intent.getSecondaryActions(), text);
        TimeScore timeScore = timeScore(intent.getTimeIntent(), task, now);
        if (timeScore.filtered()) {
            return OptionalInt.empty();
        }

        if (!passesMustMatch(intent, topicScore, participantScore, locationScore, companionScore, mainActionScore)) {
            return OptionalInt.empty();
        }

        int relationScore =
                relationScore(intent, topicScore, participantScore, locationScore, companionScore, mainActionScore);
        int domainScore = domainScore(intent.getDomainType(), text);
        int semanticScore =
                semanticBoost(intent, topicScore, participantScore, locationScore, mainActionScore, semanticSimilarity);
        int statusScore = statusScore(intent.getStatusIntents(), task.getStatus());
        int syncScore = syncScore(intent.getSyncIntent(), snapshot.getSyncState());
        int priorityScore = priorityScore(intent.getPriorityIntent(), task, now);

        int total = roleEntityScore.score()
                + mainActionScore.score()
                + secondaryActionScore.score()
                + relationScore
                + timeScore.score()
                + domainScore
                + semanticScore
                + statusScore
                + syncScore
                + priorityScore;

        boolean hasStructuredMatch = roleEntityScore.score() > 0
                || mainActionScore.score() > 0
                || secondaryActionScore.score() > 0
                || relationScore > 0;

        if (!hasStructuredMatch && semanticScore < 14) {
            return OptionalInt.empty();
        }
        if (!hasStructuredMatch && semanticScore == 0) {
            return OptionalInt.empty();
        }
        if (total <= 0) {
            return OptionalInt.empty();
        }

        return OptionalInt.of(total);
    }

    private static boolean passesMustMatch(
            SearchIntent intent,
            MatchScore topicScore,
            MatchScore participantScore,
            MatchScore locationScore,
            MatchScore companionScore,
            MatchScore mainActionScore) {
        if (intent.getQueryType() == SearchQueryType.TOPIC_SEARCH) {
            return passesTopicMust(intent, topicScore);
        }

        if (intent.getQueryType() == SearchQueryType.RELATIONAL_SEARCH) {
            return passesRelationalMust(
                    intent, topicScore, participantScore, locationScore, companionScore, mainActionScore);
        }

        return passesBroadMust(intent, topicScore, participantScore, locationScore, companionScore, mainActionScore);
    }

    private static boolean passesTopicMust(SearchIntent intent, MatchScore topicScore) {
        if (!intent.hasUsefulTopicTerms()) {
            return intent.hasUsefulMainAction();
        }
        return topicScore.score() > 0;
    }

    private static boolean passesRelationalMust(
            SearchIntent intent,
            MatchScore topicScore,
            MatchScore participantScore,
            MatchScore locationScore,
            MatchScore companionScore,
            MatchScore mainActionScore) {
        if (intent.hasUsefulTopicTerms() && topicScore.score() == 0) {
            return false;
        }
        if (intent.hasUsefulParticipantTerms() && participantScore.score() == 0) {
            return false;
        }
        if (intent.isGenericCompanionRequired() && companionScore.score() == 0) {
            return false;
        }
        if (intent.hasUsefulLocationTerms() && locationScore.score() == 0) {
            return false;
        }
        return !intent.hasUsefulMainAction() || mainActionScore.score() > 0;
    }

    private static boolean passesBroadMust(
            SearchIntent intent,
            MatchScore topicScore,
            MatchScore participantScore,
            MatchScore locationScore,
            MatchScore companionScore,
            MatchScore mainActionScore) {
        if (intent.hasUsefulTopicTerms() && topicScore.score() > 0) {
            return true;
        }
        if (intent.hasUsefulParticipantTerms() && participantScore.score() > 0) {
            return true;
        }
        if (intent.isGenericCompanionRequired() && companionScore.score() > 0) {
            return true;
        }
        if (intent.hasUsefulLocationTerms() && locationScore.score() > 0) {
            return true;
        }
        return intent.hasUsefulMainAction() && mainActionScore.score() > 0;
    }

    private static MatchScore roleEntityScore(
            MatchScore topicScore, MatchScore participantScore, MatchScore locationScore, MatchScore companionScore) {
        int activeGroups = 0;
        int matchedGroups = 0;
        int semanticGroups = 0;

        if (topicScore.active()) {
            activeGroups++;
            if (topicScore.score() > 0) {
                matchedGroups++;
            }
            if (topicScore.semanticMatched()) {
                semanticGroups++;
            }
        }
        if (participantScore.active()) {
            activeGroups++;
            if (participantScore.score() > 0) {
                matchedGroups++;
            }
            if (participantScore.semanticMatched()) {
                semanticGroups++;
            }
        }
        if (locationScore.active()) {
            activeGroups++;
            if (locationScore.score() > 0) {
                matchedGroups++;
            }
            if (locationScore.semanticMatched()) {
                semanticGroups++;
            }
        }
        if (companionScore.active()) {
            activeGroups++;
            if (companionScore.score() > 0) {
                matchedGroups++;
            }
        }

        if (activeGroups == 0 || matchedGroups == 0) {
            return MatchScore.none();
        }

        int perGroup = ROLE_ENTITY_WEIGHT / activeGroups;
        int score = matchedGroups * perGroup;
        if (semanticGroups > 0) {
            score = Math.min(ROLE_ENTITY_WEIGHT, score - Math.min(semanticGroups * 2, 6));
        }
        return new MatchScore(Math.max(score, perGroup), semanticGroups > 0, true);
    }

    private static int relationScore(
            SearchIntent intent,
            MatchScore topicScore,
            MatchScore participantScore,
            MatchScore locationScore,
            MatchScore companionScore,
            MatchScore mainActionScore) {
        if (intent.getRelationPolicy() != SearchRelationPolicy.PREFER_ALL) {
            return 0;
        }

        int expected = 0;
        int matched = 0;

        if (intent.hasUsefulMainAction()) {
            expected++;
            if (mainActionScore.score() > 0) {
                matched++;
            }
        }
        if (intent.hasUsefulParticipantTerms()) {
            expected++;
            if (participantScore.score() > 0) {
                matched++;
            }
        }
        if (intent.isGenericCompanionRequired()) {
            expected++;
            if (companionScore.score() > 0) {
                matched++;
            }
        }
        if (intent.hasUsefulLocationTerms()) {
            expected++;
            if (locationScore.score() > 0) {
                matched++;
            }
        }
        if (intent.hasUsefulTopicTerms()) {
            expected++;
            if (topicScore.score() > 0) {
                matched++;
            }
        }

        if (expected <= 1) {
            return 0;
        }
        if (matched == expected) {
            return RELATION_WEIGHT;
        }
        if (matched >= expected - 1) {
            return RELATION_WEIGHT / 3;
        }
        return -10;
    }

    private static int semanticBoost(
            SearchIntent intent,
            MatchScore topicScore,
            MatchScore participantScore,
            MatchScore locationScore,
            MatchScore mainActionScore,
            double semanticSimilarity) {
        int baseScore = semanticScore(semanticSimilarity);
        if (baseScore == 0) {
            return 0;
        }

        if (intent.getQueryType() == SearchQueryType.TOPIC_SEARCH) {
            return topicScore.score() > 0 ? baseScore : 0;
        }

        if (intent.getQueryType() == SearchQueryType.RELATIONAL_SEARCH) {
            boolean roleMatched = topicScore.score() > 0
                    || participantScore.score() > 0
                    || locationScore.score() > 0
                    || mainActionScore.score() > 0;
            return roleMatched ? baseScore : 0;
        }

        return baseScore;
    }
}
