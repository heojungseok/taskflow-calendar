package com.taskflow.calendar.domain.search;

import java.util.Locale;

public enum SearchSyncIntent {
    SYNCED,
    UNSYNCED,
    FAILED,
    ANY;

    public static SearchSyncIntent fromValue(String value) {
        if (value == null || value.isBlank()) {
            return ANY;
        }
        try {
            return SearchSyncIntent.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ANY;
        }
    }
}
