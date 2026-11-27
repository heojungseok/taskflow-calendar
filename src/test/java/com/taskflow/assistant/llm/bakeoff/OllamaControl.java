package com.taskflow.assistant.llm.bakeoff;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;

final class OllamaControl {

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    OllamaControl(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
    }

    String loadedModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/ps"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            String body =
                    client.send(request, HttpResponse.BodyHandlers.ofString()).body();
            return objectMapper
                    .readTree(body)
                    .path("models")
                    .valueStream()
                    .map(model -> model.path("name").asString("?"))
                    .toList()
                    .toString();
        } catch (Exception e) {
            return "unavailable (" + e.getClass().getSimpleName() + ")";
        }
    }

    void unload(String model) {
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of("model", model, "keep_alive", 0));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.out.println(
                    "[bakeoff] unload 실패 " + model + ": " + e.getClass().getSimpleName());
        }
    }
}
