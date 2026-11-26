package com.taskflow.assistant.llm.bakeoff;

import java.util.Map;

record CallResult(
        String candidateId,
        String feature,
        String caseId,
        int attempt,
        boolean ok,
        long latencyMs,
        boolean cold,
        Integer outputTokens,
        String errorCode,
        String errorMessage,
        Map<String, Object> output,
        Map<String, Double> metrics) {}
