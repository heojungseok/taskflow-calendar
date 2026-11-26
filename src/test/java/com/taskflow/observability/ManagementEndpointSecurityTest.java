package com.taskflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.taskflow.calendarsync.outbox.CalendarOutboxRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "outbox.worker.enabled=false"})
@AutoConfigureMetrics
class ManagementEndpointSecurityTest {

    @LocalManagementPort
    int managementPort;

    @LocalServerPort
    int serverPort;

    @Autowired
    TaskFlowMetrics metrics;

    @Autowired
    CalendarOutboxRepository outboxRepository;

    @Test
    void healthAndPrometheusAreAnonymousOnManagementPort() throws Exception {
        assertEquals(200, get("/actuator/health/readiness"));
        assertEquals(200, get("/actuator/prometheus"));
    }

    @Test
    void configuredTimersExposeHistogramBuckets() throws Exception {
        metrics.observeGeminiCall("weekly_summary", () -> "ok");
        assertEquals(200, get(serverPort, "/api/auth/session"));

        String prometheus = getBody("/actuator/prometheus");
        assertTrue(prometheus.contains("gemini_calls_seconds_bucket"));
        assertTrue(prometheus.contains("http_server_requests_seconds_bucket"));
    }

    @Test
    @DisplayName("Grafana 알림·대시보드가 읽는 Spring 지표 이름이 그대로 나온다")
    void grafanaMetricNamesStayExposed() throws Exception {
        System.gc();
        outboxRepository.count();
        assertEquals(200, get(serverPort, "/api/auth/session"));

        String prometheus = getBody("/actuator/prometheus");
        assertThat(List.of(
                        "http_server_requests_seconds_count",
                        "http_server_requests_seconds_bucket",
                        "hikaricp_connections_active",
                        "hikaricp_connections_pending",
                        "hikaricp_connections_timeout_total",
                        "hikaricp_connections_max",
                        "jvm_memory_used_bytes",
                        "jvm_memory_max_bytes",
                        "jvm_gc_pause_seconds_count",
                        "jvm_gc_pause_seconds_sum",
                        "logback_events_total",
                        "process_cpu_usage",
                        "process_uptime_seconds",
                        "spring_data_repository_invocations_seconds_count",
                        "spring_data_repository_invocations_seconds_sum"))
                .allSatisfy(name -> assertThat(prometheus).containsPattern("(?m)^" + name + "[{ ]"));
    }

    private int get(String path) throws Exception {
        return get(managementPort, path);
    }

    private int get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private String getBody(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
                .GET()
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .body();
    }
}
