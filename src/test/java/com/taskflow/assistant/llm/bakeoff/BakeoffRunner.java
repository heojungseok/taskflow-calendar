package com.taskflow.assistant.llm.bakeoff;

import java.util.ArrayList;
import java.util.List;

final class BakeoffRunner {

    private BakeoffRunner() {}

    static List<CallResult> runAll(List<Candidate> candidates, List<FeatureRunner> runners, int repeat) {
        List<CallResult> results = new ArrayList<>();
        for (Candidate candidate : candidates) {
            for (FeatureRunner runner : runners) {
                for (int attempt = 1; attempt <= repeat; attempt++) {
                    for (int caseIndex = 0; caseIndex < runner.caseIds().size(); caseIndex++) {
                        CallResult result = CallExecutor.execute(candidate, runner, caseIndex, attempt);
                        results.add(result);
                        System.out.println(describe(result));
                    }
                }
            }
        }
        return results;
    }

    private static String describe(CallResult result) {
        return "[bakeoff] %s %s %s #%d %s %dms%s%s"
                .formatted(
                        result.candidateId(),
                        result.feature(),
                        result.caseId(),
                        result.attempt(),
                        result.ok() ? "ok" : "FAIL(" + result.errorCode() + ")",
                        result.latencyMs(),
                        result.cold() ? " cold" : "",
                        result.outputTokens() == null ? "" : " tokens=" + result.outputTokens());
    }
}
