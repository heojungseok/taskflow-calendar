package com.taskflow.calendar.domain.search;

import java.util.Locale;

public enum SearchTimeIntent {
    TODAY,
    THIS_WEEK,
    THIS_MONTH,
    UPCOMING,
    RECENT,
    OVERDUE,
    DEFERRED,
    UNSPECIFIED;

    public static SearchTimeIntent fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNSPECIFIED;
        }
        try {
            return SearchTimeIntent.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNSPECIFIED;
        }
    }
}
