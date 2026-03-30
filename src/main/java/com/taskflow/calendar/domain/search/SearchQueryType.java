package com.taskflow.calendar.domain.search;

import java.util.Locale;

public enum SearchQueryType {
    TOPIC_SEARCH,
    RELATIONAL_SEARCH,
    BROAD_SEARCH;

    public static SearchQueryType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return TOPIC_SEARCH;
        }
        try {
            return SearchQueryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return TOPIC_SEARCH;
        }
    }
}
