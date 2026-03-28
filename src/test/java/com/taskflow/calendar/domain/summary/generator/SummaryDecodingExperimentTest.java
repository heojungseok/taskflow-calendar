package com.taskflow.calendar.domain.summary.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.summary.SummaryTaskSnapshot;
import com.taskflow.calendar.domain.summary.TaskSyncState;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResult;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.config.GeminiSummaryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SUMMARY_DECODE_EXPERIMENT_ENABLED", matches = "(?i)true")
class SummaryDecodingExperimentTest {

    private static final LocalDate WEEK_START = LocalDate.of(2026, 3, 23);
    private static final LocalDate WEEK_END = LocalDate.of(2026, 3, 29);
    private static final List<String> OVERCLAIM_PHRASES = List.of(
            "완료", "성공적", "순조", "문제없", "안정적", "잘 진행", "원활", "마무리"
    );
    private static final List<String> COVERAGE_GUARD_PHRASES = List.of(
            "우선순위 대표 업무 기준", "제공된 업무 기준", "포함된 업무 기준"
    );

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("summary decoding 조합별 live 실험 결과를 자동 보고서로 저장한다")
    void generateExperimentReport() throws IOException {
        GeminiSummaryProperties properties = createProperties();
        GeminiWeeklySummaryGenerator generator = new GeminiWeeklySummaryGenerator(properties, objectMapper);
        List<ExperimentScenario> scenarios = List.of(
                releaseScenario(),
                onboardingScenario()
        );
        List<Map<String, Object>> scenarioReports = new ArrayList<>();

        for (ExperimentScenario scenario : scenarios) {
            List<ExperimentConfig> configs = scenario.configs();
            List<ExperimentResult> results = new ArrayList<>();
            for (ExperimentConfig config : configs) {
                properties.setTemperature(config.temperature());
                properties.setTopK(config.topK());
                properties.setTopP(config.topP());

                SummaryGenerationTelemetry telemetry = generator.generateWithTelemetry(
                        scenario.project(),
                        scenario.syncedTasks(),
                        scenario.syncedTotalTaskCount(),
                        scenario.unsyncedTasks(),
                        scenario.unsyncedTotalTaskCount(),
                        WEEK_START,
                        WEEK_END
                );
                results.add(ExperimentResult.from(config, telemetry));
            }

            ExperimentResult baseline = results.get(0);
            List<Map<String, Object>> reportRows = new ArrayList<>();
            for (ExperimentResult result : results) {
                reportRows.add(result.toReportRow(baseline));
            }
            scenarioReports.add(Map.of(
                    "scenario", scenario.name(),
                    "results", reportRows
            ));

            assertFalse(results.get(0).syncedSummary().isBlank());
            assertFalse(results.get(0).unsyncedSummary().isBlank());
        }

        Path reportPath = Path.of("build", "reports", "summary-decoding", "experiment-report.json");
        Files.createDirectories(reportPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(reportPath.toFile(), Map.of(
                        "model", properties.getModel(),
                        "weekStart", WEEK_START,
                        "weekEnd", WEEK_END,
                        "scenarios", scenarioReports
                ));

        assertEquals(2, scenarioReports.size());
        assertTrue(Files.exists(reportPath));
    }

    private GeminiSummaryProperties createProperties() {
        GeminiSummaryProperties properties = new GeminiSummaryProperties();
        properties.setApiKey(requiredEnv("GEMINI_SUMMARY_API_KEY"));
        properties.setModel(envOrDefault("GEMINI_SUMMARY_MODEL", "gemini-3.1-flash-lite-preview"));
        properties.setBaseUrl(envOrDefault("GEMINI_SUMMARY_BASE_URL", "https://generativelanguage.googleapis.com/v1beta"));
        properties.setTimeoutSeconds(Integer.parseInt(envOrDefault("GEMINI_SUMMARY_TIMEOUT_SECONDS", "20")));
        properties.setTemperature(0.2d);
        return properties;
    }

    private SummaryTaskSnapshot snapshot(Project project,
                                         String title,
                                         String description,
                                         TaskStatus status,
                                         TaskSyncState syncState,
                                         int dueDays) {
        Task task = Task.createTask(
                project,
                title,
                description,
                null,
                null,
                LocalDateTime.of(2026, 3, 28, 10, 0).plusDays(dueDays),
                false
        );
        if (status != TaskStatus.REQUESTED) {
            task.changeStatus(status);
        }
        return SummaryTaskSnapshot.of(task, syncState, null, null, null);
    }

    private ExperimentScenario releaseScenario() {
        Project project = Project.of("Summary Decoding Experiment");
        return new ExperimentScenario(
                "release-and-sync-risk",
                project,
                List.of(
                        snapshot(project, "배포 체크리스트 정리", "이번 주 배포 전 Google OAuth redirect URI와 Gemini 키 설정, 운영 점검 항목을 재확인한다. 누락 시 배포가 지연될 수 있다.", TaskStatus.IN_PROGRESS, TaskSyncState.SYNCED, 1),
                        snapshot(project, "로그인 오류 재현 정리", "사용자 로그인 오류 재현 절차와 확인 로그를 정리해야 한다. 장애 대응 흐름 문서화가 필요한 상태다.", TaskStatus.BLOCKED, TaskSyncState.SYNCED, 2),
                        snapshot(project, "릴리즈 노트 초안", "이번 주 변경사항을 모아 릴리즈 노트 초안을 정리하고, 배포 범위를 팀과 맞춘다.", TaskStatus.REQUESTED, TaskSyncState.SYNCED, 4)
                ),
                5,
                List.of(
                        snapshot(project, "미동기 QA 일정 정리", "이번 주 QA 체크와 확인 항목이 캘린더에 반영되지 않았다. 누락 위험이 있어 정리가 필요하다.", TaskStatus.IN_PROGRESS, TaskSyncState.SYNC_DISABLED, 1),
                        snapshot(project, "권한 수정 요청 반영", "운영 계정 권한 수정 요청이 남아 있고, 담당자와 확인이 필요하다.", TaskStatus.REQUESTED, TaskSyncState.SYNC_DISABLED, 3)
                ),
                4,
                List.of(
                        new ExperimentConfig("A", 0.2d, null, null),
                        new ExperimentConfig("B", 0.2d, 20, 0.8d),
                        new ExperimentConfig("C", 1.0d, 20, 0.8d),
                        new ExperimentConfig("D", 1.0d, 40, 0.95d)
                )
        );
    }

    private ExperimentScenario onboardingScenario() {
        Project project = Project.of("Onboarding Flow Stabilization");
        return new ExperimentScenario(
                "onboarding-and-ops-followup",
                project,
                List.of(
                        snapshot(project, "회원가입 퍼널 이탈 분석", "신규 회원가입 퍼널에서 이탈 지점을 분석하고, 재현 경로와 이벤트 누락 여부를 함께 확인해야 한다.", TaskStatus.IN_PROGRESS, TaskSyncState.SYNCED, 1),
                        snapshot(project, "온보딩 메일 템플릿 수정", "가입 직후 메일 문구 수정 요청이 들어와 템플릿과 링크 동작을 다시 검토해야 한다.", TaskStatus.REQUESTED, TaskSyncState.SYNCED, 3),
                        snapshot(project, "CS 문의 답변 기준 정리", "로그인/인증 관련 반복 문의에 대한 답변 기준안을 정리해야 한다.", TaskStatus.BLOCKED, TaskSyncState.SYNCED, 5)
                ),
                6,
                List.of(
                        snapshot(project, "운영 점검 회의 일정 반영", "운영 점검 회의 일정이 아직 캘린더에 반영되지 않아 참석자 누락 가능성이 있다.", TaskStatus.REQUESTED, TaskSyncState.SYNC_DISABLED, 1),
                        snapshot(project, "권한 검토 후속 작업", "내부 운영 계정 권한 검토 후속 작업이 남아 있고 담당자 확인이 필요하다.", TaskStatus.IN_PROGRESS, TaskSyncState.SYNC_DISABLED, 2)
                ),
                4,
                List.of(
                        new ExperimentConfig("A", 0.2d, null, null),
                        new ExperimentConfig("C", 1.0d, 20, 0.8d)
                )
        );
    }

    private String requiredEnv(String key) {
        String value = System.getenv(key);
        assertTrue(value != null && !value.isBlank(), key + " must be configured for live summary decoding experiments");
        return value;
    }

    private String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static final class ExperimentConfig {
        private final String label;
        private final double temperature;
        private final Integer topK;
        private final Double topP;

        private ExperimentConfig(String label, double temperature, Integer topK, Double topP) {
            this.label = label;
            this.temperature = temperature;
            this.topK = topK;
            this.topP = topP;
        }

        private String label() {
            return label;
        }

        private double temperature() {
            return temperature;
        }

        private Integer topK() {
            return topK;
        }

        private Double topP() {
            return topP;
        }
    }

    private static final class ExperimentScenario {
        private final String name;
        private final Project project;
        private final List<SummaryTaskSnapshot> syncedTasks;
        private final int syncedTotalTaskCount;
        private final List<SummaryTaskSnapshot> unsyncedTasks;
        private final int unsyncedTotalTaskCount;
        private final List<ExperimentConfig> configs;

        private ExperimentScenario(String name,
                                   Project project,
                                   List<SummaryTaskSnapshot> syncedTasks,
                                   int syncedTotalTaskCount,
                                   List<SummaryTaskSnapshot> unsyncedTasks,
                                   int unsyncedTotalTaskCount,
                                   List<ExperimentConfig> configs) {
            this.name = name;
            this.project = project;
            this.syncedTasks = syncedTasks;
            this.syncedTotalTaskCount = syncedTotalTaskCount;
            this.unsyncedTasks = unsyncedTasks;
            this.unsyncedTotalTaskCount = unsyncedTotalTaskCount;
            this.configs = configs;
        }

        private String name() {
            return name;
        }

        private Project project() {
            return project;
        }

        private List<SummaryTaskSnapshot> syncedTasks() {
            return syncedTasks;
        }

        private int syncedTotalTaskCount() {
            return syncedTotalTaskCount;
        }

        private List<SummaryTaskSnapshot> unsyncedTasks() {
            return unsyncedTasks;
        }

        private int unsyncedTotalTaskCount() {
            return unsyncedTotalTaskCount;
        }

        private List<ExperimentConfig> configs() {
            return configs;
        }
    }

    private static final class ExperimentResult {
        private final String label;
        private final double temperature;
        private final Integer topK;
        private final Double topP;
        private final int requestBodyLength;
        private final int promptTokens;
        private final int candidateTokens;
        private final int totalTokens;
        private final int summaryTextLength;
        private final int hallucinationRiskScore;
        private final int coverageGuardScore;
        private final int structureComplianceScore;
        private final String syncedSummary;
        private final String unsyncedSummary;

        private ExperimentResult(String label,
                                 double temperature,
                                 Integer topK,
                                 Double topP,
                                 int requestBodyLength,
                                 int promptTokens,
                                 int candidateTokens,
                                 int totalTokens,
                                 int summaryTextLength,
                                 int hallucinationRiskScore,
                                 int coverageGuardScore,
                                 int structureComplianceScore,
                                 String syncedSummary,
                                 String unsyncedSummary) {
            this.label = label;
            this.temperature = temperature;
            this.topK = topK;
            this.topP = topP;
            this.requestBodyLength = requestBodyLength;
            this.promptTokens = promptTokens;
            this.candidateTokens = candidateTokens;
            this.totalTokens = totalTokens;
            this.summaryTextLength = summaryTextLength;
            this.hallucinationRiskScore = hallucinationRiskScore;
            this.coverageGuardScore = coverageGuardScore;
            this.structureComplianceScore = structureComplianceScore;
            this.syncedSummary = syncedSummary;
            this.unsyncedSummary = unsyncedSummary;
        }

        private static ExperimentResult from(ExperimentConfig config, SummaryGenerationTelemetry telemetry) {
            WeeklySummaryResult synced = telemetry.getSections().getSynced();
            WeeklySummaryResult unsynced = telemetry.getSections().getUnsynced();
            String syncedSummary = synced != null ? synced.getSummary() : "";
            String unsyncedSummary = unsynced != null ? unsynced.getSummary() : "";
            String combined = (syncedSummary + " " + unsyncedSummary).trim();

            return new ExperimentResult(
                    config.label(),
                    config.temperature(),
                    config.topK(),
                    config.topP(),
                    telemetry.getRequestBodyLength(),
                    telemetry.getPromptTokens(),
                    telemetry.getCandidateTokens(),
                    telemetry.getTotalTokens(),
                    combined.length(),
                    scoreHallucinationRisk(combined),
                    scoreCoverageGuard(combined),
                    scoreStructureCompliance(telemetry),
                    syncedSummary,
                    unsyncedSummary
            );
        }

        private Map<String, Object> toReportRow(ExperimentResult baseline) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label);
            row.put("temperature", temperature);
            row.put("topK", topK);
            row.put("topP", topP);
            row.put("requestBodyLength", requestBodyLength);
            row.put("promptTokens", promptTokens);
            row.put("candidateTokens", candidateTokens);
            row.put("totalTokens", totalTokens);
            row.put("promptTokensDeltaVsBaseline", promptTokens - baseline.promptTokens);
            row.put("totalTokensDeltaVsBaseline", totalTokens - baseline.totalTokens);
            row.put("summaryTextLength", summaryTextLength);
            row.put("hallucinationRiskScore", hallucinationRiskScore);
            row.put("coverageGuardScore", coverageGuardScore);
            row.put("structureComplianceScore", structureComplianceScore);
            row.put("syncedSummary", syncedSummary);
            row.put("unsyncedSummary", unsyncedSummary);
            return row;
        }

        private static int scoreHallucinationRisk(String text) {
            int matches = 0;
            for (String phrase : OVERCLAIM_PHRASES) {
                if (text.contains(phrase)) {
                    matches++;
                }
            }
            return Math.max(0, 100 - matches * 20);
        }

        private static int scoreCoverageGuard(String text) {
            int matches = 0;
            for (String phrase : COVERAGE_GUARD_PHRASES) {
                if (text.contains(phrase)) {
                    matches++;
                }
            }
            return Math.min(100, matches * 50);
        }

        private static int scoreStructureCompliance(SummaryGenerationTelemetry telemetry) {
            int score = 100;
            score -= penalty(telemetry.getSections().getSynced());
            score -= penalty(telemetry.getSections().getUnsynced());
            return Math.max(0, score);
        }

        private static int penalty(WeeklySummaryResult section) {
            if (section == null) {
                return 50;
            }
            int penalty = 0;
            if (section.getSummary() == null || section.getSummary().isBlank()) {
                penalty += 40;
            }
            if (sentenceCount(section.getSummary()) < 2 || sentenceCount(section.getSummary()) > 4) {
                penalty += 20;
            }
            if (section.getHighlights().size() > 3) {
                penalty += 10;
            }
            if (section.getRisks().size() > 3) {
                penalty += 10;
            }
            if (section.getNextActions().size() > 3) {
                penalty += 10;
            }
            return penalty;
        }

        private static int sentenceCount(String summary) {
            if (summary == null || summary.isBlank()) {
                return 0;
            }
            String normalized = summary.replace("?", ".")
                    .replace("!", ".")
                    .replace("다.", "다|");
            int count = 0;
            for (String sentence : normalized.split("[.|]")) {
                if (!sentence.isBlank()) {
                    count++;
                }
            }
            return count;
        }

        private String syncedSummary() {
            return syncedSummary;
        }

        private String unsyncedSummary() {
            return unsyncedSummary;
        }
    }
}
