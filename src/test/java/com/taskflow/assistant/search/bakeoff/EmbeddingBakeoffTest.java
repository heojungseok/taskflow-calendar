package com.taskflow.assistant.search.bakeoff;

import com.taskflow.assistant.search.bakeoff.EmbeddingFixture.FixtureQuery;
import com.taskflow.assistant.search.bakeoff.EmbeddingFixture.FixtureTask;
import com.taskflow.assistant.search.bakeoff.RetrievalMetrics.QueryScore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/**
 * 임베딩 후보를 같은 픽스처로 비교한다. 실제 모델을 부르므로 EMBEDDING_BAKEOFF_ENABLED=true일 때만 돈다.
 * 결과: build/embedding-bakeoff/report.md
 */
@EnabledIfEnvironmentVariable(named = "EMBEDDING_BAKEOFF_ENABLED", matches = "true")
class EmbeddingBakeoffTest {

    private static final Path OUT = Path.of("build/embedding-bakeoff");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compareCandidates() throws Exception {
        String ollamaBaseUrl = env("EMBEDDING_BAKEOFF_OLLAMA_BASE_URL", "http://localhost:11434");
        EmbeddingFixture fixture = EmbeddingFixture.load(objectMapper);
        EmbeddingVectors vectors = new EmbeddingVectors(
                objectMapper, OUT.resolve("cache"), ollamaBaseUrl, env("EMBEDDING_BAKEOFF_GEMINI_API_KEY", ""));
        String installed = installedOllamaModels(ollamaBaseUrl);

        List<String> documents =
                fixture.tasks().stream().map(FixtureTask::sourceText).toList();
        List<String> queryTexts = new ArrayList<>();
        for (FixtureQuery query : fixture.queries()) {
            queryTexts.add(query.query());
            queryTexts.add(query.productionText());
        }

        BakeoffReport report = new BakeoffReport(fixture);
        for (EmbeddingCandidate candidate : EmbeddingCandidate.select(System.getenv("EMBEDDING_BAKEOFF_CANDIDATES"))) {
            if (!candidate.gemini() && !installed.contains("\"" + candidate.ollamaModel())) {
                report.skip(candidate, "Ollama에 모델이 없음(자동으로 받지 않는다)");
                continue;
            }
            if (candidate.gemini()
                    && env("EMBEDDING_BAKEOFF_GEMINI_API_KEY", "").isBlank()) {
                report.skip(candidate, "EMBEDDING_BAKEOFF_GEMINI_API_KEY 없음");
                continue;
            }
            System.out.println("[embedding-bakeoff] " + candidate.name());
            EmbeddingVectors.Timed timed = vectors.embed(candidate, documents, queryTexts);
            vectors.unload(candidate);
            report.add(
                    candidate,
                    timed,
                    score(candidate, fixture, timed.vectors(), false),
                    score(candidate, fixture, timed.vectors(), true));
        }

        Files.createDirectories(OUT);
        String markdown = report.markdown();
        Files.writeString(OUT.resolve("report.md"), markdown);
        System.out.println(markdown);
    }

    private static List<QueryScore> score(
            EmbeddingCandidate candidate, EmbeddingFixture fixture, Map<String, float[]> cache, boolean production) {
        Map<String, float[]> documents = new LinkedHashMap<>();
        for (FixtureTask task : fixture.tasks()) {
            documents.put(task.id(), resize(candidate, cache.get(candidate.documentPrefix() + task.sourceText())));
        }
        List<QueryScore> scores = new ArrayList<>();
        for (FixtureQuery query : fixture.queries()) {
            String text = production ? query.productionText() : query.query();
            float[] vector = resize(candidate, cache.get(candidate.queryPrefix() + text));
            scores.add(RetrievalMetrics.score(query, RetrievalMetrics.rank(vector, documents)));
        }
        return scores;
    }

    private static float[] resize(EmbeddingCandidate candidate, float[] vector) {
        return candidate.dimensions() > 0 ? RetrievalMetrics.truncate(vector, candidate.dimensions()) : vector;
    }

    private static String installedOllamaModels(String baseUrl) {
        try {
            return HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/api/tags"))
                                    .timeout(Duration.ofSeconds(5))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body();
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
