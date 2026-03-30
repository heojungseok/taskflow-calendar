package com.taskflow.calendar.domain.search;

import java.util.Locale;

public enum SearchRelationPolicy {
    PREFER_ALL,
    ALLOW_PARTIAL;

    public static SearchRelationPolicy fromValue(String value) {
        if (value == null || value.isBlank()) {
            return ALLOW_PARTIAL;
        }
        try {
            return SearchRelationPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ALLOW_PARTIAL;
        }
    }
}
