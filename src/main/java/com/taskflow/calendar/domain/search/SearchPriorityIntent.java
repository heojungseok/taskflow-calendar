package com.taskflow.calendar.domain.search;

import java.util.Locale;

public enum SearchPriorityIntent {
    URGENT,
    IMPORTANT,
    MUST_DO,
    DEFERRED,
    NONE;

    public static SearchPriorityIntent fromValue(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return SearchPriorityIntent.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
