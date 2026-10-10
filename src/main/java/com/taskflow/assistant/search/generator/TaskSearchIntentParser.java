package com.taskflow.assistant.search.generator;

import com.taskflow.assistant.search.SearchIntent;

public interface TaskSearchIntentParser {

    SearchIntent parse(String query);
}
