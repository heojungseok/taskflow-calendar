package com.taskflow.assistant.llm.bakeoff;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class TextMetrics {

    private static final Set<String> GENERIC_TOKENS = Set.of("정리", "확인", "작업", "점검", "관련", "요청");

    private TextMetrics() {}

    static double koreanRatio(Collection<String> texts) {
        int hangul = 0;
        int letters = 0;
        for (String text : texts) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isLetter(c)) {
                    letters++;
                    if (c >= '가' && c <= '힣') {
                        hangul++;
                    }
                }
            }
        }
        return letters == 0 ? 0.0 : (double) hangul / letters;
    }

    static String compact(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    static Set<String> distinctiveTokens(List<String> titles) {
        Set<String> all = new LinkedHashSet<>();
        for (String title : titles) {
            all.addAll(tokens(title));
        }
        Set<String> distinctive = new HashSet<>();
        for (String token : all) {
            if (GENERIC_TOKENS.contains(token)) {
                continue;
            }
            long titlesWithToken =
                    titles.stream().filter(t -> tokens(t).contains(token)).count();
            if (titles.size() <= 2 || titlesWithToken * 2 <= titles.size()) {
                distinctive.add(token);
            }
        }
        return distinctive;
    }

    static boolean containsAny(String sentence, Set<String> tokens) {
        String lower = sentence.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> tokens(String title) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
