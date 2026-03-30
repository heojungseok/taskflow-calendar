package com.taskflow.calendar.domain.search;

import java.util.Locale;

public enum SearchTargetType {
    TASK,
    PROJECT,
    MIXED;

    public static SearchTargetType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return MIXED;
        }
        try {
            return SearchTargetType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MIXED;
        }
    }
}
