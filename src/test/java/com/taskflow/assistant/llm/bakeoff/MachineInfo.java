package com.taskflow.assistant.llm.bakeoff;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;

final class MachineInfo {

    private MachineInfo() {}

    static String describe() {
        long totalBytes = 0;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            totalBytes = os.getTotalMemorySize();
        }
        return "os=%s %s, arch=%s, availableProcessors=%d, totalMemory=%.1f GiB, java=%s"
                .formatted(
                        System.getProperty("os.name"),
                        System.getProperty("os.version"),
                        System.getProperty("os.arch"),
                        Runtime.getRuntime().availableProcessors(),
                        totalBytes / (1024.0 * 1024 * 1024),
                        System.getProperty("java.version"));
    }

    static String ollamaVersion(String baseUrl, ObjectMapper objectMapper) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/api/version"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return objectMapper.readTree(response.body()).path("version").asString("unknown");
        } catch (Exception e) {
            return "unavailable (" + e.getClass().getSimpleName() + ")";
        }
    }
}
