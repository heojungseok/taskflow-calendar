package com.taskflow.assistant.search;

import static com.taskflow.assistant.search.SearchText.normalizeSingle;

import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskStatus;
import com.taskflow.planning.task.TaskSyncState;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

/** 필수 매칭 밖의 가산·감점 신호: 의미 유사도, 시간, 상태, 캘린더 동기화, 우선순위, 업무·생활 영역. */
final class SearchBoostScorer {

    private static final int TIME_WEIGHT = 10;
    private static final int STATUS_MATCH_BOOST = 6;
    private static final int STATUS_MISMATCH_PENALTY = -6;
    private static final int SYNC_MATCH_BOOST = 6;
    private static final int SYNC_MISMATCH_PENALTY = -6;
    private static final int PRIORITY_MATCH_BOOST = 8;
    private static final int DOMAIN_WEIGHT = 5;
    private static final Map<SearchDomainType, List<String>> DOMAIN_KEYWORDS = Map.of(
            SearchDomainType.WORK, List.of("배포", "릴리즈", "스프린트", "운영", "개발", "qa", "리뷰", "프로젝트", "업무"),
            SearchDomainType.PERSONAL, List.of("개인", "가족", "친구", "약속", "취미", "개인 일정"),
            SearchDomainType.LIFE, List.of("병원", "진료", "검사", "약", "장보기", "마트", "서류", "청소", "집안", "은행", "예약", "보험"));

    private static final int SEMANTIC_WEIGHT = 20;

    private SearchBoostScorer() {}

    static TimeScore timeScore(SearchTimeIntent timeIntent, Task task, LocalDateTime now) {
        LocalDateTime dueAt = task.getDueAt();
        switch (timeIntent) {
            case TODAY:
                return timeWindowScore(dueAt, now.toLocalDate(), now.toLocalDate(), true);
            case THIS_WEEK:
                LocalDate start = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate end = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                return timeWindowScore(dueAt, start, end, true);
            case THIS_MONTH:
                YearMonth yearMonth = YearMonth.from(now);
                return timeWindowScore(dueAt, yearMonth.atDay(1), yearMonth.atEndOfMonth(), true);
            case UPCOMING:
                if (dueAt == null) {
                    return TimeScore.none();
                }
                if (dueAt.isAfter(now.minusHours(1))) {
                    return new TimeScore(TIME_WEIGHT, false);
                }
                return new TimeScore(0, true);
            case RECENT:
                if (task.getUpdatedAt() != null && task.getUpdatedAt().isAfter(now.minusDays(14))) {
                    return new TimeScore(TIME_WEIGHT, false);
                }
                return TimeScore.none();
            case OVERDUE:
                if (dueAt != null && dueAt.isBefore(now)) {
                    return new TimeScore(TIME_WEIGHT, false);
                }
                return new TimeScore(0, true);
            case DEFERRED:
                if (dueAt != null && dueAt.isBefore(now)) {
                    return new TimeScore(TIME_WEIGHT, false);
                }
                if (task.getUpdatedAt() != null && task.getUpdatedAt().isBefore(now.minusDays(10))) {
                    return new TimeScore(TIME_WEIGHT / 2, false);
                }
                return TimeScore.none();
            case UNSPECIFIED:
            default:
                return TimeScore.none();
        }
    }

    private static TimeScore timeWindowScore(LocalDateTime dueAt, LocalDate start, LocalDate end, boolean hardFilter) {
        if (dueAt == null) {
            return hardFilter ? new TimeScore(0, true) : TimeScore.none();
        }
        LocalDate dueDate = dueAt.toLocalDate();
        if ((dueDate.isEqual(start) || dueDate.isAfter(start)) && (dueDate.isEqual(end) || dueDate.isBefore(end))) {
            return new TimeScore(TIME_WEIGHT, false);
        }
        return new TimeScore(0, hardFilter);
    }

    static int statusScore(List<TaskStatus> statusIntents, TaskStatus status) {
        if (statusIntents.isEmpty() || status == null) {
            return 0;
        }
        return statusIntents.contains(status) ? STATUS_MATCH_BOOST : STATUS_MISMATCH_PENALTY;
    }

    static int syncScore(SearchSyncIntent syncIntent, TaskSyncState syncState) {
        if (syncIntent == null || syncIntent == SearchSyncIntent.ANY || syncState == null) {
            return 0;
        }
        boolean matches;
        switch (syncIntent) {
            case SYNCED:
                matches = syncState == TaskSyncState.SYNCED;
                break;
            case UNSYNCED:
                matches = syncState == TaskSyncState.PENDING_SYNC
                        || syncState == TaskSyncState.SYNC_DISABLED
                        || syncState == TaskSyncState.DELETE_PENDING;
                break;
            case FAILED:
                matches = syncState == TaskSyncState.FAILED_SYNC || syncState == TaskSyncState.DELETE_FAILED;
                break;
            case ANY:
            default:
                matches = true;
                break;
        }
        return matches ? SYNC_MATCH_BOOST : SYNC_MISMATCH_PENALTY;
    }

    static int priorityScore(SearchPriorityIntent priorityIntent, Task task, LocalDateTime now) {
        if (priorityIntent == null || priorityIntent == SearchPriorityIntent.NONE) {
            return 0;
        }
        LocalDateTime dueAt = task.getDueAt();
        switch (priorityIntent) {
            case URGENT:
                return dueAt != null && dueAt.isBefore(now.plusDays(2)) ? PRIORITY_MATCH_BOOST : 0;
            case IMPORTANT:
                return task.getStatus() == TaskStatus.BLOCKED || task.getStatus() == TaskStatus.IN_PROGRESS
                        ? PRIORITY_MATCH_BOOST
                        : 0;
            case MUST_DO:
                return task.getStatus() != TaskStatus.DONE ? PRIORITY_MATCH_BOOST : 0;
            case DEFERRED:
                return dueAt != null && dueAt.isBefore(now) ? PRIORITY_MATCH_BOOST : 0;
            case NONE:
            default:
                return 0;
        }
    }

    static int domainScore(SearchDomainType domainType, String text) {
        if (domainType == null || domainType == SearchDomainType.UNKNOWN || domainType == SearchDomainType.MIXED) {
            return 0;
        }
        List<String> keywords = DOMAIN_KEYWORDS.getOrDefault(domainType, List.of());
        for (String keyword : keywords) {
            String normalizedKeyword = normalizeSingle(keyword);
            if (!normalizedKeyword.isBlank() && text.contains(normalizedKeyword)) {
                return DOMAIN_WEIGHT;
            }
        }
        if (domainType == SearchDomainType.WORK
                && matchesAnyDomain(SearchDomainType.PERSONAL, text, SearchDomainType.LIFE)) {
            return -DOMAIN_WEIGHT;
        }
        if ((domainType == SearchDomainType.PERSONAL || domainType == SearchDomainType.LIFE)
                && matchesAnyDomain(SearchDomainType.WORK, text)) {
            return -DOMAIN_WEIGHT;
        }
        return 0;
    }

    private static boolean matchesAnyDomain(SearchDomainType primary, String text, SearchDomainType... others) {
        if (matchesDomain(primary, text)) {
            return true;
        }
        for (SearchDomainType other : others) {
            if (matchesDomain(other, text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesDomain(SearchDomainType domainType, String text) {
        for (String keyword : DOMAIN_KEYWORDS.getOrDefault(domainType, List.of())) {
            String normalizedKeyword = normalizeSingle(keyword);
            if (!normalizedKeyword.isBlank() && text.contains(normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }

    static int semanticScore(double similarity) {
        if (similarity >= 0.90d) {
            return SEMANTIC_WEIGHT;
        }
        if (similarity >= 0.82d) {
            return 14;
        }
        if (similarity >= 0.74d) {
            return 8;
        }
        return 0;
    }

    static final class TimeScore {
        private final int score;
        private final boolean filtered;

        private TimeScore(int score, boolean filtered) {
            this.score = score;
            this.filtered = filtered;
        }

        private static TimeScore none() {
            return new TimeScore(0, false);
        }

        int score() {
            return score;
        }

        boolean filtered() {
            return filtered;
        }
    }
}
