package com.taskflow.assistant.search;

import static com.taskflow.assistant.search.SearchText.PARTICIPANT_HINT_TERMS;
import static com.taskflow.assistant.search.SearchText.containsAny;
import static com.taskflow.assistant.search.SearchText.normalizeSingle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 파싱된 검색 의도를 원문 검색어로 보강하고 질의 유형을 정한다. */
final class SearchIntentNormalizer {

    private static final List<String> GENERIC_COMPANION_TERMS = List.of("누군가", "누구", "아무개", "상대", "사람");

    private static final List<String> GENERIC_COMPANION_QUERY_CUES =
            List.of("누군가와", "누군가랑", "누구와", "누구랑", "아무개와", "아무개랑", "같이", "함께", "동행", "데리고", "모시고");

    private static final List<String> LOCATION_HINT_TERMS =
            List.of("병원", "치과", "약국", "은행", "마트", "주민센터", "관공서", "미용실", "공항", "학교", "회사");

    private static final List<String> LIFE_LOCATION_TERMS = List.of("병원", "치과", "약국", "은행", "마트", "주민센터", "관공서", "미용실");

    private static final List<String> VISIT_QUERY_HINTS = List.of("가는", "가기", "가야", "방문", "들르", "내원", "진료", "검사", "예약");

    private static final List<String> MEET_QUERY_HINTS = List.of("만나", "약속", "같이", "함께", "동행", "놀", "나들이", "데이트");

    private SearchIntentNormalizer() {}

    static SearchIntent localIntent(String query) {
        List<String> terms = java.util.Arrays.stream(query.trim().split("\\s+"))
                .filter(term -> !term.isBlank())
                .limit(5)
                .collect(Collectors.toList());
        return SearchIntent.of(
                query,
                SearchQueryType.TOPIC_SEARCH,
                SearchTargetType.MIXED,
                SearchDomainType.UNKNOWN,
                SearchActionIntent.UNKNOWN,
                List.of(),
                terms,
                List.of(),
                List.of(),
                SearchTimeIntent.UNSPECIFIED,
                SearchPriorityIntent.NONE,
                List.of(),
                SearchSyncIntent.ANY,
                SearchRelationPolicy.ALLOW_PARTIAL,
                1.0d,
                Map.of(),
                List.of());
    }

    static boolean shouldFallback(SearchIntent intent) {
        int structuredSignalCount = structuredSignalCount(intent);
        if (structuredSignalCount == 0) {
            return true;
        }

        return intent.getQueryType() == SearchQueryType.BROAD_SEARCH
                && structuredSignalCount == 1
                && intent.getOverallConfidence() < 0.55d;
    }

    static SearchIntent normalizeIntent(SearchIntent parsedIntent) {
        SearchIntent enrichedIntent = enrichIntentFromRawQuery(parsedIntent);
        SearchQueryType queryType = deriveQueryType(enrichedIntent);
        SearchRelationPolicy relationPolicy = queryType == SearchQueryType.RELATIONAL_SEARCH
                ? SearchRelationPolicy.PREFER_ALL
                : SearchRelationPolicy.ALLOW_PARTIAL;

        return SearchIntent.of(
                enrichedIntent.getRawQuery(),
                queryType,
                enrichedIntent.getTargetType(),
                enrichedIntent.getDomainType(),
                enrichedIntent.getMainAction(),
                enrichedIntent.getSecondaryActions(),
                enrichedIntent.getTopicTerms(),
                enrichedIntent.getParticipantTerms(),
                enrichedIntent.getLocationTerms(),
                enrichedIntent.isGenericCompanionRequired(),
                enrichedIntent.getTimeIntent(),
                enrichedIntent.getPriorityIntent(),
                enrichedIntent.getStatusIntents(),
                enrichedIntent.getSyncIntent(),
                relationPolicy,
                enrichedIntent.getOverallConfidence(),
                enrichedIntent.getFieldConfidence(),
                enrichedIntent.getSuggestedQueries());
    }

    private static SearchIntent enrichIntentFromRawQuery(SearchIntent parsedIntent) {
        String normalizedQuery = normalizeSingle(parsedIntent.getRawQuery());

        LinkedHashSet<String> participantTerms =
                new LinkedHashSet<>(sanitizeParticipantTerms(parsedIntent.getParticipantTerms()));
        participantTerms.addAll(extractParticipantHints(normalizedQuery));

        LinkedHashSet<String> locationTerms = new LinkedHashSet<>(parsedIntent.getLocationTerms());
        locationTerms.addAll(extractLocationHints(normalizedQuery));

        boolean genericCompanionRequired = parsedIntent.isGenericCompanionRequired()
                || detectGenericCompanionRequired(normalizedQuery, participantTerms, locationTerms, parsedIntent);

        SearchActionIntent mainAction = parsedIntent.getMainAction();
        LinkedHashSet<SearchActionIntent> secondaryActions = new LinkedHashSet<>(parsedIntent.getSecondaryActions());

        boolean visitPattern = !locationTerms.isEmpty() && containsAny(normalizedQuery, VISIT_QUERY_HINTS);
        boolean meetPattern = (!participantTerms.isEmpty() || genericCompanionRequired)
                && containsAny(normalizedQuery, MEET_QUERY_HINTS);

        if (visitPattern) {
            if (mainAction != SearchActionIntent.VISIT && mainAction != SearchActionIntent.UNKNOWN) {
                secondaryActions.add(mainAction);
            }
            mainAction = SearchActionIntent.VISIT;
            if (meetPattern) {
                secondaryActions.add(SearchActionIntent.MEET);
            }
        } else if (mainAction == SearchActionIntent.UNKNOWN && meetPattern) {
            mainAction = SearchActionIntent.MEET;
        }

        SearchDomainType domainType = parsedIntent.getDomainType();
        if (domainType == SearchDomainType.UNKNOWN || domainType == SearchDomainType.MIXED) {
            if (containsAny(locationTerms, LIFE_LOCATION_TERMS)) {
                domainType = SearchDomainType.LIFE;
            } else if (!participantTerms.isEmpty()) {
                domainType = SearchDomainType.PERSONAL;
            }
        }

        return SearchIntent.of(
                parsedIntent.getRawQuery(),
                parsedIntent.getQueryType(),
                parsedIntent.getTargetType(),
                domainType,
                mainAction,
                new ArrayList<>(secondaryActions),
                parsedIntent.getTopicTerms(),
                new ArrayList<>(participantTerms),
                new ArrayList<>(locationTerms),
                genericCompanionRequired,
                parsedIntent.getTimeIntent(),
                parsedIntent.getPriorityIntent(),
                parsedIntent.getStatusIntents(),
                parsedIntent.getSyncIntent(),
                parsedIntent.getRelationPolicy(),
                parsedIntent.getOverallConfidence(),
                parsedIntent.getFieldConfidence(),
                parsedIntent.getSuggestedQueries());
    }

    private static SearchQueryType deriveQueryType(SearchIntent intent) {
        if (!intent.hasUsefulStructuredSignal()) {
            return SearchQueryType.BROAD_SEARCH;
        }
        if (isRelationalQuery(intent)) {
            return SearchQueryType.RELATIONAL_SEARCH;
        }
        if (intent.hasUsefulTopicTerms() || intent.hasUsefulMainAction()) {
            return SearchQueryType.TOPIC_SEARCH;
        }
        return SearchQueryType.BROAD_SEARCH;
    }

    private static boolean isRelationalQuery(SearchIntent intent) {
        boolean hasRelationalRole = intent.hasUsefulParticipantTerms()
                || intent.hasUsefulLocationTerms()
                || intent.isGenericCompanionRequired();
        if (!hasRelationalRole) {
            return false;
        }

        int relationRoleCount = relationRoleCount(intent);
        if (relationRoleCount < 2) {
            return false;
        }

        return intent.isGenericCompanionRequired()
                || containsRelationalConnector(intent.getRawQuery())
                || intent.getQueryType() == SearchQueryType.RELATIONAL_SEARCH;
    }

    private static int structuredSignalCount(SearchIntent intent) {
        int structuredSignalCount = 0;
        if (intent.hasUsefulTopicTerms()) {
            structuredSignalCount++;
        }
        if (intent.hasUsefulMainAction()) {
            structuredSignalCount++;
        }
        if (intent.hasUsefulParticipantTerms()) {
            structuredSignalCount++;
        }
        if (intent.hasUsefulLocationTerms()) {
            structuredSignalCount++;
        }
        return structuredSignalCount;
    }

    private static int relationRoleCount(SearchIntent intent) {
        int relationRoleCount = 0;
        if (intent.hasUsefulMainAction()) {
            relationRoleCount++;
        }
        if (intent.isGenericCompanionRequired()) {
            relationRoleCount++;
        }
        if (intent.hasUsefulParticipantTerms()) {
            relationRoleCount++;
        }
        if (intent.hasUsefulLocationTerms()) {
            relationRoleCount++;
        }
        return relationRoleCount;
    }

    private static boolean containsRelationalConnector(String rawQuery) {
        String normalized = normalizeSingle(rawQuery);
        return normalized.contains("같이")
                || normalized.contains("함께")
                || normalized.contains("동행")
                || normalized.contains("이랑")
                || normalized.contains("랑")
                || normalized.contains("들과")
                || normalized.contains("하고")
                || normalized.contains("와 함께")
                || normalized.contains("과 함께")
                || normalized.contains("만나서")
                || normalized.contains("데리고");
    }

    private static List<String> extractParticipantHints(String normalizedQuery) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String candidate : PARTICIPANT_HINT_TERMS) {
            String normalizedCandidate = normalizeSingle(candidate);
            if (!normalizedCandidate.isBlank() && normalizedQuery.contains(normalizedCandidate)) {
                terms.add(candidate);
            }
        }
        return new ArrayList<>(terms);
    }

    private static List<String> sanitizeParticipantTerms(List<String> participantTerms) {
        return participantTerms.stream()
                .map(SearchText::normalizeSingle)
                .filter(term -> !term.isBlank() && !GENERIC_COMPANION_TERMS.contains(term))
                .collect(Collectors.toList());
    }

    private static boolean detectGenericCompanionRequired(
            String normalizedQuery,
            Set<String> participantTerms,
            Set<String> locationTerms,
            SearchIntent parsedIntent) {
        if (!participantTerms.isEmpty()) {
            return false;
        }
        if (!containsAny(normalizedQuery, GENERIC_COMPANION_QUERY_CUES)) {
            return false;
        }
        return !locationTerms.isEmpty() || parsedIntent.hasUsefulMainAction() || parsedIntent.hasUsefulTopicTerms();
    }

    private static List<String> extractLocationHints(String normalizedQuery) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String candidate : LOCATION_HINT_TERMS) {
            String normalizedCandidate = normalizeSingle(candidate);
            if (!normalizedCandidate.isBlank() && normalizedQuery.contains(normalizedCandidate)) {
                terms.add(candidate);
            }
        }
        return new ArrayList<>(terms);
    }
}
