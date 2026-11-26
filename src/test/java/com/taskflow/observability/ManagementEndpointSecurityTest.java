package com.taskflow.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http_server_requests_seconds_count",
                "hikaricp_connections_pending",
                "hikaricp_connections_timeout_total",
                "hikaricp_connections_max",
                "jvm_memory_used_bytes",
                "jvm_memory_max_bytes",
                "jvm_gc_pause_seconds_count",
                "logback_events_total",
                "process_cpu_usage",
                "process_uptime_seconds"
            })
    @DisplayName("Grafana 알림·대시보드가 읽는 Spring 지표 이름이 그대로 나온다")
    void grafanaMetricNamesStayExposed(String metricName) throws Exception {
        System.gc();
        assertEquals(200, get(serverPort, "/api/auth/session"));

        String prometheus = getBody("/actuator/prometheus");
        assertTrue(prometheus.contains(metricName + "{") || prometheus.contains(metricName + " "), metricName);
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
