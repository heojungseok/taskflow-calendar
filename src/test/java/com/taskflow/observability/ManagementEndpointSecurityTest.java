package com.taskflow.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "outbox.worker.enabled=false"})
@AutoConfigureObservability
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

    private int get(String path) throws Exception {
        return get(managementPort, path);
    }

    private int get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String getBody(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + managementPort + path)).GET().build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString()).body();
    }
}
