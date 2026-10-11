package com.taskflow.assistant.search;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/** 검색어와 Task 본문을 같은 규칙으로 정규화하고 비교한다. */
final class SearchText {

    static final List<String> PARTICIPANT_HINT_TERMS = List.of(
            "친구", "가족", "엄마", "아빠", "부모", "부모님", "형", "누나", "언니", "오빠", "동생", "팀원", "동료", "고객", "선생님", "배우자", "남편",
            "아내", "아이", "애인");

    private SearchText() {}

    static String normalizeText(String... values) {
        return Arrays.stream(values)
                .filter(Objects::nonNull)
                .map(SearchText::normalizeSingle)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(" "));
    }

    static String normalizeSingle(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^0-9a-zA-Z가-힣\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static boolean containsAny(String normalizedText, List<String> candidates) {
        for (String candidate : candidates) {
            String normalizedCandidate = normalizeSingle(candidate);
            if (!normalizedCandidate.isBlank() && normalizedText.contains(normalizedCandidate)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsAny(Collection<String> values, List<String> candidates) {
        for (String value : values) {
            String normalizedValue = normalizeSingle(value);
            for (String candidate : candidates) {
                String normalizedCandidate = normalizeSingle(candidate);
                if (!normalizedCandidate.isBlank() && normalizedValue.equals(normalizedCandidate)) {
                    return true;
                }
            }
        }
        return false;
    }
}
