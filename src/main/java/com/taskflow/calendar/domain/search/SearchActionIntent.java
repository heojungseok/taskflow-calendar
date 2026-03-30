package com.taskflow.calendar.domain.search;

import java.util.Locale;

public enum SearchActionIntent {
    PREPARE,
    SUBMIT,
    BUY,
    VISIT,
    MEET,
    ORGANIZE,
    FIX,
    CHECK,
    UNKNOWN;

    public static SearchActionIntent fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return SearchActionIntent.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
