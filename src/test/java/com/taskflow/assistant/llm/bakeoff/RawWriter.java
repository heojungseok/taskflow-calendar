package com.taskflow.assistant.llm.bakeoff;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

final class RawWriter {

    private RawWriter() {}

    static String render(List<CallResult> results, ObjectMapper objectMapper) {
        StringBuilder lines = new StringBuilder();
        for (CallResult result : results) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("candidate", result.candidateId());
            line.put("feature", result.feature());
            line.put("caseId", result.caseId());
            line.put("attempt", result.attempt());
            line.put("ok", result.ok());
            line.put("latencyMs", result.latencyMs());
            line.put("cold", result.cold());
            line.put("outputTokens", result.outputTokens());
            line.put("errorCode", result.errorCode());
            line.put("errorMessage", result.errorMessage());
            line.put("metrics", result.metrics());
            line.put("output", result.output());
            lines.append(objectMapper.writeValueAsString(line)).append('\n');
        }
        return lines.toString();
    }
}
