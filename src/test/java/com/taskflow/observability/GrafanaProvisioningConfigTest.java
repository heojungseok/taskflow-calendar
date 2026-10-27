package com.taskflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GrafanaProvisioningConfigTest {

    @Test
    void dashboardKeepsApprovedOperationalStructure() throws Exception {
        JsonNode dashboard =
                new ObjectMapper().readTree(Files.readString(Path.of("deploy/grafana/dashboards/taskflow.json")));

        List<JsonNode> rows = new ArrayList<>();
        dashboard.path("panels").forEach(panel -> {
            if ("row".equals(panel.path("type").asText())) {
                rows.add(panel);
            }
        });

        assertThat(rows)
                .extracting(row -> row.path("title").asText())
                .containsExactly("운영 요약", "사용자·이용", "Calendar·Outbox", "Gemini·Cache", "Backend runtime", "보안");
        assertThat(rows)
                .extracting(row -> row.path("collapsed").asBoolean())
                .containsExactly(false, false, true, true, true, true);
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
                        "nginx 로그 (IP 필터: $ip)");
        assertThat(List.of("HTTP p95", "HTTP 5xx", "Gemini 실패 (10m)"))
                .allSatisfy(title -> assertThat(findPanel(dashboard.path("panels"), title)
                                .path("type")
                                .asText())
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
                .contains("check_url: /api/auth/session", "noDataState: Alerting", "noDataState: OK")
                .contains("__dashboardUid__: taskflow", "__panelId__: \"2\"")
                .contains("**결과** 알럿 상태가 정상화됐습니다.", "**추가 확인 필요**")
                .contains("{{ len .Alerts }}건", "{{ $externalURL }}d/taskflow/taskflow")
                .doesNotContain("\n        dashboardUid:", "\n        panelId:", "__panelId__: \"13\"")
                .doesNotContain("정상 상태로 돌아왔습니다", "키·모델을 수정", "/ {{ .Labels.environment }}");
        assertThat(alerts.lines()
                        .filter(line -> line.startsWith("      - uid: taskflow-") && !line.contains("discord"))
                        .count())
                .isEqualTo(8);
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

    private List<String> panelTitles(JsonNode dashboard) {
        List<String> titles = new ArrayList<>();
        collectPanelTitles(dashboard.path("panels"), titles);
        return titles;
    }

    private void collectPanelTitles(JsonNode panels, List<String> titles) {
        panels.forEach(panel -> {
            if (!"row".equals(panel.path("type").asText())) {
                titles.add(panel.path("title").asText());
            }
            collectPanelTitles(panel.path("panels"), titles);
        });
    }

    private JsonNode findPanel(JsonNode panels, String title) {
        for (JsonNode panel : panels) {
            if (title.equals(panel.path("title").asText())) {
                return panel;
            }
            JsonNode nested = findPanel(panel.path("panels"), title);
            if (!nested.isMissingNode()) {
                return nested;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
