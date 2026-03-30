package com.taskflow.calendar.domain.search;

import java.util.Locale;

public enum SearchDomainType {
    WORK,
    PERSONAL,
    LIFE,
    MIXED,
    UNKNOWN;

    public static SearchDomainType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return SearchDomainType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
