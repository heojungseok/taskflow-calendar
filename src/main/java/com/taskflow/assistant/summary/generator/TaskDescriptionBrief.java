package com.taskflow.assistant.summary.generator;

import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Task 설명을 프롬프트용 짧은 요약(중요 문장 1~2개)과 신호(urgent, risk 등)로 줄인다. 요약과 추천이 같이 쓴다. */
public final class TaskDescriptionBrief {

    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("(?<=[.!?])\\s+|\\n+");
    private static final List<String> URGENCY_KEYWORDS =
            List.of("긴급", "urgent", "asap", "즉시", "오늘", "오늘 안", "today", "critical", "반드시");
    private static final List<String> RISK_KEYWORDS =
            List.of("리스크", "risk", "실패", "failure", "누락", "지연", "문제", "오류", "retry", "차단", "blocked");
    private static final List<String> DEPENDENCY_KEYWORDS = List.of("의존", "dependency", "승인", "검토", "외부", "협업", "대기");
    private static final List<String> DELIVERABLE_KEYWORDS =
            List.of("발표", "문서", "체크리스트", "보고", "정리", "캡처", "데모", "산출물", "공유");
    private static final List<String> CONFIG_KEYWORDS =
            List.of("oauth", "redirect uri", "api key", "gemini", "환경 변수", "env", "config", "설정");
    private static final List<String> DEMO_KEYWORDS = List.of("sprint review", "review", "데모", "화면 캡처", "캡처");
    private static final List<String> DOC_KEYWORDS = List.of("문서", "체크리스트", "가이드", "정리", "공유", "보고");
    private static final List<String> PRESERVED_KEYWORDS =
            List.of("Google OAuth", "Gemini API key", "OAuth", "API key", "배포", "Sprint Review", "체크리스트", "캡처", "데모");

    private TaskDescriptionBrief() {}

    public static String compressDescription(Task task) {
        String description = task.getDescription();
        if (description == null || description.isBlank()) {
            return null;
        }

        int maxChars = maxDescriptionLength(task.getStatus());
        int maxSentences = allowSecondSentence(task, description) ? 2 : 1;
        List<SentenceCandidate> candidates = sentenceCandidates(description);

        if (candidates.isEmpty()) {
            return truncate(description, maxChars);
        }

        List<SentenceCandidate> selected = candidates.stream()
                .sorted(Comparator.comparingInt(SentenceCandidate::getScore)
                        .reversed()
                        .thenComparingInt(SentenceCandidate::getIndex))
                .limit(maxSentences)
                .sorted(Comparator.comparingInt(SentenceCandidate::getIndex))
                .collect(Collectors.toList());

        String combined = selected.stream().map(SentenceCandidate::getSentence).collect(Collectors.joining(" "));
        String normalized = normalizeWhitespace(combined);
        if (normalized.isBlank()) {
            normalized = normalizeWhitespace(description);
        }
        return truncate(normalized, maxChars);
    }

    public static List<String> descriptionSignals(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }

        String normalized = description.toLowerCase(Locale.ROOT);
        Set<String> signals = new LinkedHashSet<>();

        if (containsAny(normalized, URGENCY_KEYWORDS)) {
            signals.add("urgent");
        }
        if (containsAny(normalized, RISK_KEYWORDS)) {
            signals.add("risk");
        }
        if (containsAny(normalized, DEPENDENCY_KEYWORDS)) {
            signals.add("dependency");
        }
        if (containsAny(normalized, DELIVERABLE_KEYWORDS)) {
            signals.add("deliverable");
        }
        if (containsAny(normalized, CONFIG_KEYWORDS)) {
            signals.add("config");
        }
        if (containsAny(normalized, DEMO_KEYWORDS)) {
            signals.add("demo");
        }
        if (containsAny(normalized, DOC_KEYWORDS)) {
            signals.add("doc");
        }

        return List.copyOf(signals);
    }

    private static int maxDescriptionLength(TaskStatus status) {
        if (status == TaskStatus.BLOCKED || status == TaskStatus.IN_PROGRESS) {
            return 120;
        }
        if (status == TaskStatus.DONE) {
            return 48;
        }
        return 88;
    }

    private static boolean allowSecondSentence(Task task, String description) {
        if (task.getStatus() != TaskStatus.BLOCKED && task.getStatus() != TaskStatus.IN_PROGRESS) {
            return false;
        }
        return descriptionSignals(description).size() >= 2;
    }

    private static List<SentenceCandidate> sentenceCandidates(String description) {
        String normalized = normalizeWhitespace(description);
        if (normalized.isBlank()) {
            return List.of();
        }

        String[] rawSentences = SENTENCE_SPLIT_PATTERN.split(normalized, -1);
        List<SentenceCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (String rawSentence : rawSentences) {
            String sentence = normalizeWhitespace(rawSentence);
            if (sentence.isBlank()) {
                continue;
            }
            candidates.add(new SentenceCandidate(index++, sentence, scoreSentence(sentence)));
        }

        if (candidates.isEmpty()) {
            candidates.add(new SentenceCandidate(0, normalized, scoreSentence(normalized)));
        }
        return candidates;
    }

    private static int scoreSentence(String sentence) {
        String normalized = sentence.toLowerCase(Locale.ROOT);
        int score = 0;

        score += containsAny(normalized, RISK_KEYWORDS) ? 50 : 0;
        score += containsAny(normalized, URGENCY_KEYWORDS) ? 40 : 0;
        score += containsAny(normalized, DELIVERABLE_KEYWORDS) ? 30 : 0;
        score += containsAny(normalized, DEPENDENCY_KEYWORDS) ? 25 : 0;
        score += countHits(normalized, CONFIG_KEYWORDS) * 30;
        score += containsAny(normalized, DEMO_KEYWORDS) ? 18 : 0;
        score += containsAny(normalized, DOC_KEYWORDS) ? 15 : 0;
        score += countHits(normalized, PRESERVED_KEYWORDS) * 25;
        score += containsScheduleSignal(normalized) ? 15 : 0;

        return score;
    }

    private static boolean containsScheduleSignal(String normalized) {
        return normalized.contains("월")
                || normalized.contains("화")
                || normalized.contains("수")
                || normalized.contains("목")
                || normalized.contains("금")
                || normalized.contains("토")
                || normalized.contains("일")
                || normalized.contains("마감")
                || normalized.contains("오전")
                || normalized.contains("오후")
                || normalized.contains("배포");
    }

    private static boolean containsAny(String normalized, List<String> keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int countHits(String normalized, List<String> keywords) {
        int hits = 0;
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    static String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int maxChars) {
        String normalized = normalizeWhitespace(value);
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        int end = maxChars;
        while (end > 0 && !Character.isWhitespace(normalized.charAt(end - 1))) {
            end--;
        }
        if (end < maxChars / 2) {
            end = maxChars;
        }

        return normalized.substring(0, end).trim();
    }

    private static final class SentenceCandidate {
        private final int index;
        private final String sentence;
        private final int score;

        private SentenceCandidate(int index, String sentence, int score) {
            this.index = index;
            this.sentence = sentence;
            this.score = score;
        }

        private int getIndex() {
            return index;
        }

        private String getSentence() {
            return sentence;
        }

        private int getScore() {
            return score;
        }
    }
}
