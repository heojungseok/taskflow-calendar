package com.taskflow.calendar.domain.search.generator;

import com.taskflow.calendar.domain.search.SearchIntent;

public interface TaskSearchIntentParser {

    SearchIntent parse(String query);
}
