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

        OllamaControl ollama = new OllamaControl(config.ollamaBaseUrl(), objectMapper);
        String loadedAtStart = ollama.loadedModels();
        List<CallResult> results = BakeoffRunner.runAll(candidates, runners, config.repeat(), ollama);
        List<ConcurrencyResult> concurrency = probeConcurrency(config, candidates, ollama);

        List<String> candidateIds = candidates.stream().map(Candidate::id).toList();
        String report = ReportWriter.render(
                header(config, candidateIds, runners, loadedAtStart), candidateIds, runners, results, concurrency);
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
            runners.add(new SearchRunner(loader.searchCases(), objectMapper));
        }
        if (config.runs("recommendation")) {
            runners.add(new RecommendationRunner(loader.recommendationCases(), objectMapper));
        }
        if (config.runs("summary")) {
            runners.add(new SummaryRunner(loader.summaryCases()));
        }
        return runners;
    }

    private List<ConcurrencyResult> probeConcurrency(
            BakeoffConfig config, List<Candidate> candidates, OllamaControl ollama) throws Exception {
        List<ConcurrencyResult> results = new ArrayList<>();
        if (config.concurrency() <= 0) {
            return results;
        }
        SearchRunner searchRunner = new SearchRunner(new FixtureLoader(objectMapper).searchCases(), objectMapper);
        for (Candidate candidate : candidates) {
            if (candidate.isOllama()) {
                results.add(ConcurrencyProbe.run(candidate, searchRunner, config.concurrency()));
                ollama.unload(candidate.model());
            }
        }
        return results;
    }

    private List<String> header(
            BakeoffConfig config, List<String> candidateIds, List<FeatureRunner> runners, String loadedAtStart) {
        List<String> counts = runners.stream()
                .map(runner -> runner.feature() + "=" + runner.caseIds().size() * config.repeat())
                .toList();
        return List.of(
                "생성 시각: " + Instant.now(),
                "기준일: " + BakeoffFixtures.TODAY,
                "머신: " + MachineInfo.describe(),
                "Ollama: " + config.ollamaBaseUrl() + " (version "
                        + MachineInfo.ollamaVersion(config.ollamaBaseUrl(), objectMapper) + ")",
                "시작 시점 Ollama 적재 모델(/api/ps): " + loadedAtStart,
                "후보: " + candidateIds + " (후보가 끝나면 Ollama 모델을 unload하고, 후보의 첫 호출만 cold로 표시)",
                "기능: " + config.features() + ", repeat=" + config.repeat() + ", concurrency=" + config.concurrency(),
                "후보당 기능별 호출 수(케이스 수 x repeat): " + counts,
                "Ollama 설정: num_ctx=" + CandidateFactory.OLLAMA_NUM_CTX + ", keep_alive="
                        + CandidateFactory.OLLAMA_KEEP_ALIVE + ", timeout=" + CandidateFactory.OLLAMA_TIMEOUT_SECONDS
                        + "s(운영 기본값)",
                "실행: LLM_BAKEOFF_ENABLED=true ./gradlew cleanTest test --tests '*LlmBakeoffTest' (cleanTest 없이는"
                        + " 환경변수가 바뀌어도 up-to-date로 건너뛴다)");
    }

    private void write(String report, String raw) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(OUTPUT_DIR.resolve("report.md"), report);
        Files.writeString(OUTPUT_DIR.resolve("raw.jsonl"), raw);
    }
}
