package com.taskflow.assistant.search;

import static com.taskflow.assistant.search.SearchText.PARTICIPANT_HINT_TERMS;
import static com.taskflow.assistant.search.SearchText.containsAny;
import static com.taskflow.assistant.search.SearchText.normalizeSingle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 검색 의도의 단어·행동·영역이 Task 본문과 얼마나 맞는지 잰다. */
final class SearchTermMatcher {

    private static final int MAIN_ACTION_WEIGHT = 20;

    private static final int SECONDARY_ACTION_WEIGHT = 10;

    private static final Set<String> GENERIC_TERMS = Set.of("일정", "작업", "업무", "할일", "할 일", "것");

    private static final Set<String> LEISURE_TOPIC_TERMS = Set.of("놀기", "놀이", "놀", "나들이", "여가", "데이트");

    private static final List<String> LEISURE_TOPIC_EXPANSIONS =
            List.of("놀기", "놀이", "놀", "나들이", "여가", "데이트", "만남", "약속", "외출", "놀러", "소풍", "피크닉");

    private static final Map<String, List<String>> TOPIC_PHRASE_EXPANSIONS =
            Map.of("화상 회의", List.of("화상 회의", "온라인 회의", "줌 회의", "줌 미팅", "구글 밋", "구글밋", "google meet", "zoom meeting"));

    private static final List<String> TASK_COMPANION_CUES = List.of("같이", "함께", "동행", "데리고", "모시고");

    private static final Map<SearchActionIntent, List<String>> ACTION_KEYWORDS = Map.of(
            SearchActionIntent.PREPARE, List.of("준비", "체크리스트", "초안", "자료", "발표", "세팅", "작성"),
            SearchActionIntent.SUBMIT, List.of("제출", "업로드", "전송", "신청", "등록"),
            SearchActionIntent.BUY, List.of("구매", "장보기", "주문", "결제", "마트", "사기"),
            SearchActionIntent.VISIT, List.of("방문", "병원", "진료", "검사", "내원", "가야", "가기"),
            SearchActionIntent.MEET, List.of("만나", "약속", "동행", "놀", "놀이", "나들이", "여가", "데이트"),
            SearchActionIntent.ORGANIZE, List.of("정리", "정돈", "분류", "정리하기"),
            SearchActionIntent.FIX, List.of("수정", "해결", "복구", "버그", "장애", "패치", "고치"),
            SearchActionIntent.CHECK, List.of("확인", "검토", "점검", "리뷰", "체크", "팔로업", "follow up"));

    private SearchTermMatcher() {}

    static MatchScore topicScore(List<String> topicTerms, String text) {
        List<String> normalizedTerms = expandTopicTerms(topicTerms).stream()
                .map(SearchText::normalizeSingle)
                .filter(term -> !term.isBlank() && !GENERIC_TERMS.contains(term))
                .collect(Collectors.toList());
        if (normalizedTerms.isEmpty()) {
            return MatchScore.inactive();
        }

        boolean exact = false;
        boolean semantic = false;
        for (String term : normalizedTerms) {
            if (text.contains(term)) {
                exact = true;
                continue;
            }

            List<String> tokens = Arrays.stream(term.split("\\s+"))
                    .map(SearchText::normalizeSingle)
                    .filter(token -> !token.isBlank() && !GENERIC_TERMS.contains(token))
                    .collect(Collectors.toList());
            if (tokens.isEmpty()) {
                continue;
            }

            if (tokens.size() == 1) {
                String token = tokens.get(0);
                String root = token.length() > 2 ? token.substring(0, token.length() - 1) : token;
                if (root.length() >= 2 && text.contains(root)) {
                    semantic = true;
                }
                continue;
            }

            long matchedTokens = tokens.stream().filter(text::contains).count();
            if (matchedTokens == tokens.size()) {
                semantic = true;
            }
        }

        if (exact) {
            return MatchScore.exact();
        }
        if (semantic) {
            return MatchScore.semantic();
        }
        return MatchScore.none();
    }

    private static List<String> expandTopicTerms(List<String> topicTerms) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String term : topicTerms) {
            String normalizedTerm = normalizeSingle(term);
            if (normalizedTerm.isBlank()) {
                continue;
            }
            expanded.add(normalizedTerm);
            if (LEISURE_TOPIC_TERMS.contains(normalizedTerm)) {
                expanded.addAll(LEISURE_TOPIC_EXPANSIONS);
            }
            expanded.addAll(TOPIC_PHRASE_EXPANSIONS.getOrDefault(normalizedTerm, List.of()));
        }
        return new ArrayList<>(expanded);
    }

    static MatchScore termScore(List<String> terms, String text) {
        List<String> normalizedTerms = terms.stream()
                .map(SearchText::normalizeSingle)
                .filter(term -> !term.isBlank() && !GENERIC_TERMS.contains(term))
                .collect(Collectors.toList());
        if (normalizedTerms.isEmpty()) {
            return MatchScore.inactive();
        }

        boolean exact = false;
        boolean semantic = false;
        for (String term : normalizedTerms) {
            for (String candidate : expandSearchTerm(term)) {
                if (text.contains(candidate)) {
                    exact = true;
                    break;
                }
                for (String token : candidate.split("\\s+", -1)) {
                    if (token.length() >= 2 && text.contains(token)) {
                        semantic = true;
                        break;
                    }
                }
                if (exact || semantic) {
                    break;
                }
            }
        }

        if (exact) {
            return MatchScore.exact();
        }
        if (semantic) {
            return MatchScore.semantic();
        }
        return MatchScore.none();
    }

    static MatchScore companionScore(SearchIntent intent, String text) {
        if (!intent.isGenericCompanionRequired()) {
            return MatchScore.inactive();
        }

        if (containsAny(text, TASK_COMPANION_CUES)) {
            return MatchScore.exact();
        }

        for (String candidate : PARTICIPANT_HINT_TERMS) {
            String normalizedCandidate = normalizeSingle(candidate);
            if (!normalizedCandidate.isBlank() && text.contains(normalizedCandidate)) {
                return MatchScore.exact();
            }
        }

        return MatchScore.none();
    }

    private static List<String> expandSearchTerm(String term) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        expanded.add(term);

        if (term.endsWith("들") && term.length() > 1) {
            expanded.add(term.substring(0, term.length() - 1));
        }

        if (term.endsWith("들과") && term.length() > 2) {
            expanded.add(term.substring(0, term.length() - 2));
        }

        if (term.endsWith("이들") && term.length() > 2) {
            expanded.add(term.substring(0, term.length() - 2));
        }

        return new ArrayList<>(expanded);
    }

    static MatchScore actionScore(SearchActionIntent actionIntent, String text) {
        if (actionIntent == null || actionIntent == SearchActionIntent.UNKNOWN) {
            return MatchScore.inactive();
        }
        List<String> keywords = ACTION_KEYWORDS.getOrDefault(actionIntent, List.of());
        if (keywords.isEmpty()) {
            return MatchScore.none();
        }

        boolean exact = false;
        boolean semantic = false;
        for (String keyword : keywords) {
            String normalizedKeyword = normalizeSingle(keyword);
            if (normalizedKeyword.isBlank()) {
                continue;
            }
            if (text.contains(normalizedKeyword)) {
                exact = true;
                continue;
            }
            String root = normalizedKeyword.length() > 2
                    ? normalizedKeyword.substring(0, normalizedKeyword.length() - 1)
                    : normalizedKeyword;
            if (root.length() >= 2 && text.contains(root)) {
                semantic = true;
            }
        }

        if (exact) {
            return new MatchScore(MAIN_ACTION_WEIGHT, false, true);
        }
        if (semantic) {
            return new MatchScore(Math.max(MAIN_ACTION_WEIGHT - 8, 8), true, true);
        }
        return MatchScore.none();
    }

    static MatchScore secondaryActionScore(List<SearchActionIntent> actionIntents, String text) {
        List<SearchActionIntent> effectiveIntents = actionIntents.stream()
                .filter(intent -> intent != SearchActionIntent.UNKNOWN)
                .collect(Collectors.toList());
        if (effectiveIntents.isEmpty()) {
            return MatchScore.inactive();
        }

        int matched = 0;
        int semanticMatched = 0;
        for (SearchActionIntent intent : effectiveIntents) {
            MatchScore score = actionScore(intent, text);
            if (score.score() > 0) {
                matched++;
                if (score.semanticMatched()) {
                    semanticMatched++;
                }
            }
        }

        if (matched == 0) {
            return MatchScore.none();
        }

        int perAction = Math.max(SECONDARY_ACTION_WEIGHT / effectiveIntents.size(), 4);
        int score = Math.min(SECONDARY_ACTION_WEIGHT, matched * perAction);
        if (semanticMatched > 0) {
            score = Math.max(score - semanticMatched, 4);
        }
        return new MatchScore(score, semanticMatched > 0, true);
    }
}
