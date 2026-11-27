package com.taskflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.engine.jdbc.spi.SQLExceptionLogging;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GrafanaProvisioningConfigTest {

    @Test
    void dashboardKeepsApprovedOperationalStructure() throws Exception {
        JsonNode dashboard =
                new ObjectMapper().readTree(Files.readString(Path.of("deploy/grafana/dashboards/taskflow.json")));

        List<JsonNode> rows = new ArrayList<>();
        dashboard.path("panels").forEach(panel -> {
            if ("row".equals(panel.path("type").asString())) {
                rows.add(panel);
            }
        });

        assertThat(rows)
                .extracting(row -> row.path("title").asString())
                .containsExactly(
                        "운영 요약", "사용자·이용", "Calendar·Outbox", "Gemini·Cache", "Backend runtime", "DB·자원", "보안");
        assertThat(rows)
                .extracting(row -> row.path("collapsed").asBoolean())
                .containsExactly(false, false, true, true, true, true, true);
        assertThat(panelTitles(dashboard))
                .contains(
                        "Backend UP",
                        "HTTP p95",
                        "HTTP 5xx",
                        "Google 가입 사용자",
                        "Google 신규 사용자 (24h)",
                        "활성 DEMO 세션",
                        "Gemini 호출",
                        "Gemini p95",
                        "Cache 처리율",
                        "JVM heap",
                        "Hikari pending",
                        "막힌·스캐너 경로 접근 (IP별, 5m)",
                        "ID 대입 404 (IP별 서로 다른 ID, 10m)",
                        "OAuth 로그인 실패 (이유별, 1h)",
                        "nginx 로그 (IP 필터: $ip)",
                        "DB 지표 수집",
                        "DB 연결 수",
                        "DB 크기",
                        "컨테이너 CPU",
                        "컨테이너 메모리",
                        "자원 수집 지연");
        assertThat(List.of("HTTP p95", "HTTP 5xx", "Gemini 실패 (10m)"))
                .allSatisfy(title -> assertThat(findPanel(dashboard.path("panels"), title)
                                .path("type")
                                .asString())
                        .isEqualTo("timeseries"));
    }

    @Test
    void metricsAndAlertsKeepApprovedContract() throws Exception {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        String alerts = Files.readString(Path.of("deploy/grafana/provisioning/alerting/taskflow.yml"));

        assertThat(application).contains("percentiles-histogram:", "http.server.requests: true", "gemini_calls: true");
        assertThat(alerts)
                .contains("group_by: [environment]", ".Alerts.Firing", ".Alerts.Resolved")
                .doesNotContain(".CommonAnnotations")
                .contains("uid: taskflow-gemini-quota-exhausted", "uid: taskflow-gemini-repeated-failures")
                .contains("summary: DEMO 트래픽 급증", "summary: Backend 메트릭 수집 중단")
                .contains("uid: taskflow-database-metrics-down", "summary: DB 메트릭 수집 중단")
                .contains("uid: taskflow-database-connections-high", "summary: DB 연결 수 한도 근접")
                .contains("uid: taskflow-container-memory-high", "summary: Docker 메모리 압박")
                .contains("uid: taskflow-container-cpu-high", "summary: 컨테이너 CPU 과다")
                .contains("uid: taskflow-container-stats-stale", "summary: 컨테이너 자원 수집 중단")
                .contains("check_url: /api/auth/session", "noDataState: Alerting", "noDataState: OK")
                .contains("__dashboardUid__: taskflow", "__panelId__: \"2\"")
                .contains("**결과** 알럿 상태가 정상화됐습니다.", "**추가 확인 필요**")
                .contains("{{ len .Alerts }}건", "{{ $externalURL }}d/taskflow/taskflow")
                .doesNotContain("\n        dashboardUid:", "\n        panelId:", "__panelId__: \"13\"")
                .doesNotContain("정상 상태로 돌아왔습니다", "키·모델을 수정", "/ {{ .Labels.environment }}");
        assertThat(alerts.lines()
                        .filter(line -> line.startsWith("      - uid: taskflow-") && !line.contains("discord"))
                        .count())
                .isEqualTo(15);
    }

    @Test
    void logPipelineSilentAlertSearchesTheHeartbeatLineTheBackendWrites() throws Exception {
        String silentRule = rule("taskflow-log-pipeline-silent");

        assertThat(silentRule)
                .contains("summary: Backend 로그 수집 끊김")
                .contains("|~ `^INFO +\\S*" + LogPipelineHeartbeat.class.getSimpleName() + " : "
                        + LogPipelineHeartbeat.MESSAGE + "$`")
                .contains("noDataState: Alerting")
                .doesNotContain("noDataState: OK");
    }

    @Test
    void backendErrorAlertCountsErrorsAndHibernateJdbcErrorWarningsAndIgnoresLokiOutages() throws Exception {
        String errorRule = rule("taskflow-backend-error-logs");
        String logback = Files.readString(Path.of("src/main/resources/logback-spring.xml"));
        String jdbcErrorLogger = SQLExceptionLogging.ERROR_NAME;

        assertThat(logback).contains("<suffixPattern>backend: %-5level %logger{36} : %msg");
        assertThat(jdbcErrorLogger).hasSizeLessThan(36);
        assertThat(errorRule)
                .contains("summary: Backend ERROR·DB 오류 로그 발생")
                .contains("|~ `^(ERROR |WARN +" + jdbcErrorLogger.replace(".", "\\.") + " )` [5m]")
                .contains("execErrState: OK")
                .doesNotContain("execErrState: Error");
    }

    @Test
    void grafanaShowsKoreanUiAndKstTaskflowHomeWithoutPublicSharing() throws Exception {
        String compose = Files.readString(Path.of("compose.production.yml"));
        JsonNode dashboard =
                new ObjectMapper().readTree(Files.readString(Path.of("deploy/grafana/dashboards/taskflow.json")));

        assertThat(compose)
                .contains("GF_USERS_DEFAULT_LANGUAGE: ko-KR")
                .contains(
                        "GF_SERVER_ROOT_URL: \"${TASKFLOW_GRAFANA_ROOT_URL:-http://localhost:${TASKFLOW_GRAFANA_PORT:-3001}/}\"")
                .contains("GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH: /var/lib/grafana-dashboards/taskflow.json")
                .contains("GF_USERS_ALLOW_SIGN_UP: \"false\"", "GF_SNAPSHOTS_ENABLED: \"false\"")
                .contains("GF_PUBLIC_DASHBOARDS_ENABLED: \"false\"", "GF_ANALYTICS_REPORTING_ENABLED: \"false\"");
        assertThat(dashboard.path("timezone").asString()).isEqualTo("Asia/Seoul");
    }

    @Test
    void firingDiscordMessageLinksToSilenceAndRecentLogsWithinDiscordLength() throws Exception {
        String alerts = Files.readString(Path.of("deploy/grafana/provisioning/alerting/taskflow.yml"));

        assertThat(alerts)
                .contains(
                        "alerting/silence/new?alertmanager=grafana&matcher=alertname%3D{{ .Labels.alertname | urlquery }}")
                .doesNotContain(".SilenceURL")
                .contains("{{- if $backendLogs }}", "{{- if $nginxLogs }}")
                .contains("explore?schemaVersion=1&orgId=1&panes=")
                .contains("\"expr\":\"{service=\\\"backend\\\"}\"")
                .doesNotContain("\"range\":{\"from\"");
    }

    @Test
    void discordMessageSwitchesToOneLineSummaryFromThreeAlertsToStayWithinDiscordLength() throws Exception {
        String alerts = Files.readString(Path.of("deploy/grafana/provisioning/alerting/taskflow.yml"));

        assertThat(alerts)
                .contains("{{- if le (len .Alerts) 2 }}{{ template \"taskflow.discord.detail\" . }}"
                        + "{{ else }}{{ template \"taskflow.discord.summary\" . }}{{ end }}")
                .contains("{{ define \"taskflow.discord.summary\" }}", "- [발생] {{ .Annotations.summary }}")
                .contains("{{ $externalURL }}alerting/list");
    }

    private String rule(String uid) throws Exception {
        String alerts = Files.readString(Path.of("deploy/grafana/provisioning/alerting/taskflow.yml"));
        int start = alerts.indexOf("uid: " + uid);
        int next = alerts.indexOf("\n      - uid: ", start + 1);
        return alerts.substring(start, next < 0 ? alerts.length() : next);
    }

    @Test
    void securityAlertsKeepApprovedContract() throws Exception {
        String security = Files.readString(Path.of("deploy/grafana/provisioning/alerting/security.yml"));
        String policies = Files.readString(Path.of("deploy/grafana/provisioning/alerting/taskflow.yml"));

        // 정석님이 정한 알림 5개와 한국어 [보안] 메시지, 종료 메시지 없음, 배포 검증 요청 제외(2026-10-27)
        assertThat(security.lines()
                        .filter(line -> line.startsWith("      - uid: taskflow-sec-"))
                        .count())
                .isEqualTo(5);
        assertThat(security)
                .contains("disableResolveMessage: true", "[보안][WARNING] TaskFlow", "service: security")
                .contains("summary: 경로 훑기 의심", "summary: 인증 실패 반복", "summary: 요청 수 제한 동작")
                .contains("summary: ID 대입 의심", "summary: OAuth 로그인 실패")
                .contains("ua != \"taskflow-boundary-check\"", "{{ $externalURL }}d/taskflow/taskflow");
        assertThat(policies)
                .contains("receiver: Discord security", "[service, =, security]", "group_by: [alertname, ip]");
    }

    @Test
    void everyAlertLinksToAnExistingDashboardPanelWithUniqueIds() throws Exception {
        JsonNode dashboard =
                new ObjectMapper().readTree(Files.readString(Path.of("deploy/grafana/dashboards/taskflow.json")));
        List<String> ids = new ArrayList<>();
        collectPanelIds(dashboard.path("panels"), ids);
        List<String> linked = new ArrayList<>();
        for (String file : List.of("taskflow.yml", "security.yml")) {
            Files.readString(Path.of("deploy/grafana/provisioning/alerting/" + file))
                    .lines()
                    .map(String::trim)
                    .filter(line -> line.startsWith("__panelId__:"))
                    .map(line -> line.substring("__panelId__:".length()).trim().replace("\"", ""))
                    .forEach(linked::add);
        }

        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).containsAll(linked);
    }

    @Test
    void databaseAlertsWatchExporterReachabilityAndShowTheValueTheyEvaluate() throws Exception {
        String alerts = Files.readString(Path.of("deploy/grafana/provisioning/alerting/taskflow.yml"));
        JsonNode dashboard =
                new ObjectMapper().readTree(Files.readString(Path.of("deploy/grafana/dashboards/taskflow.json")));

        assertThat(alerts).contains("up{job=\"postgres\"}", "pg_scrape_collector_success");
        assertThat(findPanel(dashboard.path("panels"), "DB 연결 수").toString()).contains("sum(pg_stat_activity_count)");
        assertThat(findPanel(dashboard.path("panels"), "DB 지표 수집")
                        .path("fieldConfig")
                        .path("defaults")
                        .path("noValue")
                        .asString())
                .isEqualTo("DOWN");
    }

    @Test
    void containerAlertsNameTheServiceAndStaleCollectionAlertsOnNoData() throws Exception {
        String alerts = Files.readString(Path.of("deploy/grafana/provisioning/alerting/taskflow.yml"));

        assertThat(rule("taskflow-container-stats-stale"))
                .contains("noDataState: Alerting", "file=\"/textfile/containers.prom\"")
                .doesNotContain("noDataState: OK");
        assertThat(rule("taskflow-container-cpu-high")).contains("max by (service)", "avg_over_time");
        assertThat(rule("taskflow-container-memory-high")).contains("taskflow_docker_memory_total_bytes");
        assertThat(alerts).doesNotContain("Mac mini", "LaunchAgent");
    }

    private List<String> panelTitles(JsonNode dashboard) {
        List<String> titles = new ArrayList<>();
        collectPanelTitles(dashboard.path("panels"), titles);
        return titles;
    }

    private void collectPanelTitles(JsonNode panels, List<String> titles) {
        panels.forEach(panel -> {
            if (!"row".equals(panel.path("type").asString())) {
                titles.add(panel.path("title").asString());
            }
            collectPanelTitles(panel.path("panels"), titles);
        });
    }

    private void collectPanelIds(JsonNode panels, List<String> ids) {
        panels.forEach(panel -> {
            ids.add(panel.path("id").asString());
            collectPanelIds(panel.path("panels"), ids);
        });
    }

    private JsonNode findPanel(JsonNode panels, String title) {
        for (JsonNode panel : panels) {
            if (title.equals(panel.path("title").asString())) {
                return panel;
            }
            JsonNode nested = findPanel(panel.path("panels"), title);
            if (!nested.isMissingNode()) {
                return nested;
            }
        }
        return tools.jackson.databind.node.MissingNode.getInstance();
    }
}
