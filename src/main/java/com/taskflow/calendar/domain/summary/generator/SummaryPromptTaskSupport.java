package com.taskflow.calendar.domain.summary.generator;

import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.domain.summary.SummaryTaskSnapshot;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SummaryPromptTaskSupport {

    private static final int DEFAULT_EVENT_DURATION_HOURS = 1;
    private static final int RECENT_UPDATE_HOURS = 24;
    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    private static final List<String> URGENCY_KEYWORDS = List.of(
            "긴급", "urgent", "asap", "즉시", "오늘", "오늘 안", "today", "critical", "반드시"
    );
    private static final List<String> RISK_KEYWORDS = List.of(
            "리스크", "risk", "실패", "failure", "누락", "지연", "문제", "오류", "retry", "차단", "blocked"
    );
    private static final List<String> DEPENDENCY_KEYWORDS = List.of(
            "의존", "dependency", "승인", "검토", "외부", "협업", "대기"
    );
    private static final List<String> DELIVERABLE_KEYWORDS = List.of(
            "발표", "문서", "체크리스트", "보고", "정리", "캡처", "데모", "산출물", "공유"
    );
    private static final List<String> CONFIG_KEYWORDS = List.of(
            "oauth", "redirect uri", "api key", "gemini", "환경 변수", "env", "config", "설정"
    );
    private static final List<String> DEMO_KEYWORDS = List.of(
            "sprint review", "review", "데모", "화면 캡처", "캡처"
    );
    private static final List<String> DOC_KEYWORDS = List.of(
            "문서", "체크리스트", "가이드", "정리", "공유", "보고"
    );
    private static final List<String> PRESERVED_KEYWORDS = List.of(
            "Google OAuth", "Gemini API key", "OAuth", "API key", "배포", "Sprint Review", "체크리스트", "캡처", "데모"
    );

    public Map<String, Object> toPromptTaskPayload(SummaryTaskSnapshot snapshot, LocalDate weekStart, LocalDate weekEnd) {
        Task task = snapshot.getTask();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.getId());
        item.put("title", task.getTitle());
        item.put("status", task.getStatus().name());

        putIfPresent(item, "startAt", task.getStartAt());
        putIfPresent(item, "dueAt", task.getDueAt());

        boolean recentlyUpdated = isRecentlyUpdated(task);
        if (recentlyUpdated) {
            item.put("recentlyUpdated", true);
            putIfPresent(item, "updatedAt", task.getUpdatedAt());
        }

        if (Boolean.TRUE.equals(task.getCalendarSyncEnabled())) {
            item.put("calendarSyncEnabled", true);
        }

        item.put("syncState", snapshot.getSyncState().name());
        putIfPresent(item, "lastOutboxStatus", enumName(snapshot.getLatestOutboxStatus()));
        if (snapshot.getLatestOutboxStatus() == OutboxStatus.FAILED) {
            putIfPresent(item, "lastOutboxError", normalizeWhitespace(snapshot.getLatestOutboxError()));
        }

        putIfTrue(item, "isOverdue", isOverdue(task));
        putIfTrue(item, "isDeadlineThisWeek", isDeadlineThisWeek(task, weekStart, weekEnd));
        putIfTrue(item, "isEventOverlappingThisWeek", isEventOverlappingThisWeek(task, weekStart, weekEnd));

        String descBrief = compressDescription(task);
        putIfPresent(item, "descBrief", descBrief);

        List<String> signals = descriptionSignals(task.getDescription());
        if (!signals.isEmpty()) {
            item.put("descSignals", signals);
        }

        return item;
    }

    public int descBriefChars(Task task) {
        String descBrief = compressDescription(task);
        return descBrief == null ? 0 : descBrief.length();
    }

    public String compressDescription(Task task) {
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
                .sorted(Comparator.comparingInt(SentenceCandidate::getScore).reversed()
                        .thenComparingInt(SentenceCandidate::getIndex))
                .limit(maxSentences)
                .sorted(Comparator.comparingInt(SentenceCandidate::getIndex))
                .collect(Collectors.toList());

        String combined = selected.stream()
                .map(SentenceCandidate::getSentence)
                .collect(Collectors.joining(" "));
        String normalized = normalizeWhitespace(combined);
        if (normalized.isBlank()) {
            normalized = normalizeWhitespace(description);
        }
        return truncate(normalized, maxChars);
    }

    public List<String> descriptionSignals(String description) {
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

    private int maxDescriptionLength(TaskStatus status) {
        if (status == TaskStatus.BLOCKED || status == TaskStatus.IN_PROGRESS) {
            return 120;
        }
        if (status == TaskStatus.DONE) {
            return 48;
        }
        return 88;
    }

    private boolean allowSecondSentence(Task task, String description) {
        if (task.getStatus() != TaskStatus.BLOCKED && task.getStatus() != TaskStatus.IN_PROGRESS) {
            return false;
        }
        return descriptionSignals(description).size() >= 2;
    }

    private List<SentenceCandidate> sentenceCandidates(String description) {
        String normalized = normalizeWhitespace(description);
        if (normalized.isBlank()) {
            return List.of();
        }

        String[] rawSentences = SENTENCE_SPLIT_PATTERN.split(normalized);
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

    private int scoreSentence(String sentence) {
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

    private boolean containsScheduleSignal(String normalized) {
        return normalized.contains("월") || normalized.contains("화") || normalized.contains("수")
                || normalized.contains("목") || normalized.contains("금") || normalized.contains("토")
                || normalized.contains("일") || normalized.contains("마감") || normalized.contains("오전")
                || normalized.contains("오후") || normalized.contains("배포");
    }

    private boolean containsAny(String normalized, List<String> keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private int countHits(String normalized, List<String> keywords) {
        int hits = 0;
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    private boolean isRecentlyUpdated(Task task) {
        if (task.getUpdatedAt() == null) {
            return false;
        }
        return task.getUpdatedAt().isAfter(LocalDateTime.now().minusHours(RECENT_UPDATE_HOURS));
    }

    private boolean isOverdue(Task task) {
        return task.getDueAt() != null
                && task.getDueAt().toLocalDate().isBefore(LocalDate.now())
                && task.getStatus() != TaskStatus.DONE;
    }

    private boolean isDeadlineThisWeek(Task task, LocalDate weekStart, LocalDate weekEnd) {
        if (task.getDueAt() == null) {
            return false;
        }

        LocalDate dueDate = task.getDueAt().toLocalDate();
        return !dueDate.isBefore(weekStart) && !dueDate.isAfter(weekEnd);
    }

    private boolean isEventOverlappingThisWeek(Task task, LocalDate weekStart, LocalDate weekEnd) {
        if (task.getDueAt() == null) {
            return false;
        }

        if (task.getStartAt() != null && task.getStartAt().isAfter(task.getDueAt())) {
            return false;
        }

        LocalDate eventStartDate = task.getStartAt() != null
                ? task.getStartAt().toLocalDate()
                : task.getDueAt().minusHours(DEFAULT_EVENT_DURATION_HOURS).toLocalDate();
        LocalDate eventEndDate = task.getDueAt().toLocalDate();
        return !eventStartDate.isAfter(weekEnd) && !eventEndDate.isBefore(weekStart);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String && ((String) value).isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private void putIfTrue(Map<String, Object> target, String key, boolean value) {
        if (value) {
            target.put(key, true);
        }
    }

    private String enumName(OutboxStatus status) {
        return status != null ? status.name() : null;
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxChars) {
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
