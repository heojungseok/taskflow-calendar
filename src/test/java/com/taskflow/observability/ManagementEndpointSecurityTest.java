package com.taskflow.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "outbox.worker.enabled=false"})
@AutoConfigureObservability
class ManagementEndpointSecurityTest {

    @LocalManagementPort
    int managementPort;

    @Test
    void healthAndPrometheusAreAnonymousOnManagementPort() throws Exception {
        assertEquals(200, get("/actuator/health/readiness"));
        assertEquals(200, get("/actuator/prometheus"));
    }

    private int get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + managementPort + path)).GET().build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
