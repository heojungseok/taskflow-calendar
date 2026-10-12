package com.taskflow.assistant.summary.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.assistant.summary.GeminiSummaryProperties;
import com.taskflow.assistant.summary.SummaryBucket;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.planning.project.Project;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 주간 요약 Gemini 요청 본문(시스템 지시, 입력 JSON, 생성 설정)과 그 지표를 만든다. */
final class WeeklySummaryPromptBuilder {

    private final GeminiSummaryProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SummaryPromptTaskSupport promptTaskSupport = new SummaryPromptTaskSupport();

    WeeklySummaryPromptBuilder(GeminiSummaryProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    PreparedRequest prepareRequest(
            Project project,
            List<TaskSyncSnapshot> syncedTasks,
            int syncedTotalTaskCount,
            List<TaskSyncSnapshot> unsyncedTasks,
            int unsyncedTotalTaskCount,
            LocalDate weekStart,
            LocalDate weekEnd) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("system_instruction", createContent(systemInstruction()));
            requestBody.put(
                    "contents",
                    List.of(createContent(userPrompt(
                            project,
                            syncedTasks,
                            syncedTotalTaskCount,
                            unsyncedTasks,
                            unsyncedTotalTaskCount,
                            weekStart,
                            weekEnd))));

            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("temperature", properties.getTemperature());
            if (effectiveTopK() != null) {
                generationConfig.put("topK", effectiveTopK());
            }
            if (effectiveTopP() != null) {
                generationConfig.put("topP", effectiveTopP());
            }
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseJsonSchema", WeeklySummaryResponseFormat.responseJsonSchema());
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", 0));
            requestBody.put("generationConfig", generationConfig);

            String serializedRequestBody = objectMapper.writeValueAsString(requestBody);
            return new PreparedRequest(
                    serializedRequestBody, buildPromptMetrics(serializedRequestBody, syncedTasks, unsyncedTasks));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build Gemini request body", e);
        }
    }

    private Map<String, Object> createContent(String text) {
        return Map.of("parts", List.of(Map.of("text", text)));
    }

    private String systemInstruction() {
        return """
               당신은 프로젝트 업무를 주간 단위로 정리하는 요약 보조자다.
               응답은 반드시 JSON 스키마를 따라라.
               summary, highlights, risks, nextActions의 문자열 값은 모두 한국어 존댓말로 작성하라.
               제공된 Task 데이터만 사용하고, 입력에 없는 사실은 추측하지 마라.
               synced와 unsynced는 각자 제공된 데이터만 기준으로 작성하라.\
               """;
    }

    private String userPrompt(
            Project project,
            List<TaskSyncSnapshot> syncedTasks,
            int syncedTotalTaskCount,
            List<TaskSyncSnapshot> unsyncedTasks,
            int unsyncedTotalTaskCount,
            LocalDate weekStart,
            LocalDate weekEnd)
            throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectName", project.getName());
        LocalDate today = LocalDate.now(clock);
        payload.put("today", today);
        payload.put("weekStart", weekStart);
        payload.put("weekEnd", weekEnd);
        payload.put(
                "instructions",
                promptInstructions(syncedTasks, syncedTotalTaskCount, unsyncedTasks, unsyncedTotalTaskCount));
        payload.put(
                "synced",
                sectionPayload(SummaryBucket.SYNCED, syncedTasks, syncedTotalTaskCount, weekStart, weekEnd, today));
        payload.put(
                "unsynced",
                sectionPayload(
                        SummaryBucket.UNSYNCED, unsyncedTasks, unsyncedTotalTaskCount, weekStart, weekEnd, today));

        return "아래 입력 JSON을 바탕으로 synced와 unsynced 두 섹션만 반환하라.\n" + objectMapper.writeValueAsString(payload);
    }

    private List<String> promptInstructions(
            List<TaskSyncSnapshot> syncedTasks,
            int syncedTotalTaskCount,
            List<TaskSyncSnapshot> unsyncedTasks,
            int unsyncedTotalTaskCount) {
        List<String> instructions = new ArrayList<>();
        instructions.add("이번 주 기준으로만 요약하라.");
        instructions.add("synced는 일정 흐름과 일정상 리스크 중심으로, unsynced는 누락 위험과 반영 필요성 중심으로 작성하라.");
        instructions.add("summary는 2~4문장으로, highlights·risks·nextActions는 각 0~3개로 제한하라.");
        instructions.add("리스크·차단 상태는 risks에, 바로 할 일은 nextActions에 작성하라.");
        instructions.add("summary는 status·syncState·outbox 결과만 사실로 사용하고, 완료·성공적·순조·문제없음·위험없음 같은 표현은 근거 없으면 쓰지 마라.");
        instructions.add("summary와 목록 문장은 모두 한국어 존댓말로 작성하라.");

        if (hasPartialCoverage(syncedTasks, syncedTotalTaskCount)
                || hasPartialCoverage(unsyncedTasks, unsyncedTotalTaskCount)) {
            instructions.add("includedTaskCount가 totalTaskCount보다 작으면, 제공된 tasks만 우선순위 대표 업무로 보고 그 범위 안에서만 요약하라.");
            instructions.add("partial coverage 섹션에서는 첫 문장부터 '우선순위 대표 업무 기준'처럼 범위를 밝히고 섹션 전체를 단정하지 마라.");
        } else {
            instructions.add("summary의 첫 문장은 섹션 전체 경향을 먼저 요약하고, 다음 문장에서 대표 task를 연결하라.");
        }

        return instructions;
    }

    private Map<String, Object> sectionPayload(
            SummaryBucket bucket,
            List<TaskSyncSnapshot> tasks,
            int totalTaskCount,
            LocalDate weekStart,
            LocalDate weekEnd,
            LocalDate today) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("focus", bucket.getPromptFocus());
        section.put("totalTaskCount", totalTaskCount);
        section.put("includedTaskCount", tasks.size());
        section.put("summaryLeadHint", summaryLeadHint(bucket, tasks, totalTaskCount));
        if (hasPartialCoverage(tasks, totalTaskCount)) {
            section.put("coverageHint", "세부 요약은 우선순위 대표 업무만 근거로 삼고, 포함되지 않은 task까지 확장하지 않는다.");
        }
        section.put("tasks", taskPayload(tasks, weekStart, weekEnd, today));
        return section;
    }

    private String summaryLeadHint(SummaryBucket bucket, List<TaskSyncSnapshot> tasks, int totalTaskCount) {
        boolean partialCoverage = hasPartialCoverage(tasks, totalTaskCount);
        if (bucket == SummaryBucket.UNSYNCED) {
            if (partialCoverage) {
                return "첫 문장부터 우선순위 대표 업무 기준으로 누락 위험과 반영 필요 사항을 요약하고, 이후 문장에서 대표 task를 연결한다.";
            }
            return "첫 문장은 미동기화 업무 전반의 상태를 요약하고, 이후 문장에서 주요 task를 연결한다.";
        }
        if (partialCoverage) {
            return "첫 문장부터 우선순위 대표 업무 기준으로 핵심 일정과 리스크를 요약하고, 이후 문장에서 대표 task를 연결한다.";
        }
        return "첫 문장은 이번 주 일정 전반을 요약하고, 이후 문장에서 주요 일정과 리스크를 연결한다.";
    }

    private boolean hasPartialCoverage(List<TaskSyncSnapshot> tasks, int totalTaskCount) {
        return totalTaskCount > 0 && tasks.size() < totalTaskCount;
    }

    private List<Map<String, Object>> taskPayload(
            List<TaskSyncSnapshot> tasks, LocalDate weekStart, LocalDate weekEnd, LocalDate today) {
        List<Map<String, Object>> payload = new ArrayList<>();

        for (TaskSyncSnapshot snapshot : tasks) {
            payload.add(promptTaskSupport.toPromptTaskPayload(snapshot, weekStart, weekEnd, today));
        }

        return payload;
    }

    Integer effectiveTopK() {
        Integer topK = properties.getTopK();
        if (topK == null || topK < 1) {
            return null;
        }
        return topK;
    }

    Double effectiveTopP() {
        Double topP = properties.getTopP();
        if (topP == null || topP <= 0.0d || topP > 1.0d) {
            return null;
        }
        return topP;
    }

    private PromptMetrics buildPromptMetrics(
            String requestBody, List<TaskSyncSnapshot> syncedTasks, List<TaskSyncSnapshot> unsyncedTasks) {
        return new PromptMetrics(
                requestBody.getBytes(StandardCharsets.UTF_8).length,
                sha256(requestBody),
                syncedTasks.size(),
                unsyncedTasks.size(),
                totalDescBriefChars(syncedTasks),
                totalDescBriefChars(unsyncedTasks));
    }

    private int totalDescBriefChars(List<TaskSyncSnapshot> tasks) {
        int total = 0;
        for (TaskSyncSnapshot snapshot : tasks) {
            total += promptTaskSupport.descBriefChars(snapshot.getTask());
        }
        return total;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    static final class PreparedRequest {
        private final String requestBody;
        private final PromptMetrics metrics;

        private PreparedRequest(String requestBody, PromptMetrics metrics) {
            this.requestBody = requestBody;
            this.metrics = metrics;
        }

        String getRequestBody() {
            return requestBody;
        }

        PromptMetrics getMetrics() {
            return metrics;
        }
    }
}
