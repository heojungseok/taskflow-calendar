package com.taskflow.assistant.llm.bakeoff;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "LLM_BAKEOFF_ENABLED", matches = "(?i)true")
class LlmBakeoffTest {

    private static final Path OUTPUT_DIR = Path.of("build", "llm-bakeoff");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("후보별로 실제 생성기를 돌려 기능별 품질과 지연 보고서를 저장한다")
    void runBakeoff() throws Exception {
        BakeoffConfig config = BakeoffConfig.fromEnv();
        List<Candidate> candidates = candidates(config);
        List<FeatureRunner> runners = runners(config);
        assertFalse(candidates.isEmpty(), "후보가 없다. Ollama 모델이나 LLM_BAKEOFF_GEMINI_API_KEY를 설정한다");
        assertFalse(runners.isEmpty(), "LLM_BAKEOFF_FEATURES에 search, recommendation, summary 중 하나가 필요하다");

        List<CallResult> results = BakeoffRunner.runAll(candidates, runners, config.repeat());
        List<ConcurrencyResult> concurrency = probeConcurrency(config, candidates);

        List<String> candidateIds = candidates.stream().map(Candidate::id).toList();
        String report = ReportWriter.render(header(config, candidateIds), candidateIds, runners, results, concurrency);
        write(report, RawWriter.render(results, objectMapper));
        System.out.println(report);
    }

    private List<Candidate> candidates(BakeoffConfig config) {
        CandidateFactory factory = new CandidateFactory(objectMapper);
        List<Candidate> candidates = new ArrayList<>();
        for (String model : config.ollamaModels()) {
            candidates.add(factory.ollama(config, model));
        }
        if (config.geminiEnabled()) {
            candidates.add(factory.gemini(config));
        } else {
            System.out.println("[bakeoff] LLM_BAKEOFF_GEMINI_API_KEY 없음: Gemini를 건너뛴다");
        }
        return candidates;
    }

    private List<FeatureRunner> runners(BakeoffConfig config) {
        FixtureLoader loader = new FixtureLoader(objectMapper);
        List<FeatureRunner> runners = new ArrayList<>();
        if (config.runs("search")) {
            runners.add(new SearchRunner(loader.searchCases()));
        }
        if (config.runs("recommendation")) {
            runners.add(new RecommendationRunner(loader.recommendationCases()));
        }
        if (config.runs("summary")) {
            runners.add(new SummaryRunner(loader.summaryCases()));
        }
        return runners;
    }

    private List<ConcurrencyResult> probeConcurrency(BakeoffConfig config, List<Candidate> candidates)
            throws Exception {
        List<ConcurrencyResult> results = new ArrayList<>();
        if (config.concurrency() <= 0) {
            return results;
        }
        SearchRunner searchRunner = new SearchRunner(new FixtureLoader(objectMapper).searchCases());
        for (Candidate candidate : candidates) {
            if (candidate.isOllama()) {
                results.add(ConcurrencyProbe.run(candidate, searchRunner, config.concurrency()));
            }
        }
        return results;
    }

    private List<String> header(BakeoffConfig config, List<String> candidateIds) {
        return List.of(
                "생성 시각: " + Instant.now(),
                "기준일: " + BakeoffFixtures.TODAY,
                "머신: " + MachineInfo.describe(),
                "Ollama: " + config.ollamaBaseUrl() + " (version "
                        + MachineInfo.ollamaVersion(config.ollamaBaseUrl(), objectMapper) + ")",
                "후보: " + candidateIds,
                "기능: " + config.features() + ", repeat=" + config.repeat() + ", concurrency=" + config.concurrency(),
                "Ollama 설정: num_ctx=8192, keep_alive=10m, timeout=120s");
    }

    private void write(String report, String raw) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(OUTPUT_DIR.resolve("report.md"), report);
        Files.writeString(OUTPUT_DIR.resolve("raw.jsonl"), raw);
    }
}
