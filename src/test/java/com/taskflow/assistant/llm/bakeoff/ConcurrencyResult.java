package com.taskflow.assistant.llm.bakeoff;

import java.util.List;

record ConcurrencyResult(
        String candidateId, int requests, int successes, long wallMs, List<Long> latenciesMs, List<String> errors) {}
