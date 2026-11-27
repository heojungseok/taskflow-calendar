package com.taskflow.assistant.search.bakeoff;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;

/** 캐시 없이 warm-up 뒤 질의를 하나씩 임베딩해 지연을 잰다. 부하 모델을 주면 그 모델이 생성하는 동안 한 번 더 잰다. */
final class LatencyProbe {

    private static final String LOAD_PROMPT =
            "다음 주 프로젝트 진행 상황을 세 문단으로 자세히 요약해 줘. " + "배포 일정, 남은 버그, 테스트 현황, 위험 요소, 다음 행동을 각각 적어.";

    record Result(List<Long> idleMillis, List<Long> loadMillis, long documentMillis, int documents, int loadCalls) {}

    private final String ollamaBaseUrl;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    LatencyProbe(String ollamaBaseUrl) {
        this.ollamaBaseUrl = ollamaBaseUrl.replaceAll("/+$", "");
    }

    Result measure(EmbeddingModel model, List<String> queries, List<String> documents, int rounds, String loadModel)
            throws InterruptedException {
        model.call(new EmbeddingRequest(List.of(queries.get(0)), null));
        long started = System.nanoTime();
        for (int from = 0; from < documents.size(); from += 16) {
            model.call(new EmbeddingRequest(documents.subList(from, Math.min(from + 16, documents.size())), null));
        }
        long documentMillis = (System.nanoTime() - started) / 1_000_000;

        List<Long> idle = timeQueries(model, queries, rounds);
        if (loadModel == null || loadModel.isBlank()) {
            return new Result(idle, List.of(), documentMillis, documents.size(), 0);
        }
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger loadCalls = new AtomicInteger();
        Thread load = Thread.ofPlatform().start(() -> {
            while (running.get()) {
                generate(loadModel, "10m");
                loadCalls.incrementAndGet();
            }
        });
        Thread.sleep(3_000);
        List<Long> underLoad = timeQueries(model, queries, 1);
        running.set(false);
        load.join();
        generate(loadModel, "0");
        return new Result(idle, underLoad, documentMillis, documents.size(), loadCalls.get());
    }

    private static List<Long> timeQueries(EmbeddingModel model, List<String> queries, int rounds) {
        List<Long> millis = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            for (String query : queries) {
                long started = System.nanoTime();
                model.call(new EmbeddingRequest(List.of(query), null));
                millis.add((System.nanoTime() - started) / 1_000_000);
            }
        }
        return millis;
    }

    private void generate(String model, String keepAlive) {
        String prompt = "0".equals(keepAlive) ? "" : LOAD_PROMPT;
        String body =
                "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"keep_alive\":%s,\"options\":{\"num_predict\":200}}"
                        .formatted(model, prompt, "0".equals(keepAlive) ? "0" : "\"" + keepAlive + "\"");
        try {
            client.send(
                    HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/generate"))
                            .timeout(Duration.ofMinutes(2))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            System.out.println("[embedding-bakeoff] 부하 생성 실패: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
