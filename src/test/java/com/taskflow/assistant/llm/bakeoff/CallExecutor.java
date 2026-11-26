package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.common.exception.BusinessException;
import java.util.Map;

final class CallExecutor {

    private static final int MESSAGE_LIMIT = 300;

    private CallExecutor() {}

    static CallResult execute(Candidate candidate, FeatureRunner runner, int caseIndex, int attempt, boolean cold) {
        String caseId = runner.caseIds().get(caseIndex);
        MeteredLlmProvider.reset();
        long startedAt = System.nanoTime();
        try {
            FeatureRunner.Outcome outcome = runner.run(caseIndex, candidate.generators());
            long latencyMs = elapsedMs(startedAt);
            return new CallResult(
                    candidate.id(),
                    runner.feature(),
                    caseId,
                    attempt,
                    true,
                    latencyMs,
                    cold,
                    MeteredLlmProvider.lastOutputTokens(),
                    null,
                    null,
                    outcome.output(),
                    outcome.metrics());
        } catch (BusinessException e) {
            return failure(
                    candidate,
                    runner,
                    caseId,
                    attempt,
                    cold,
                    startedAt,
                    e.getErrorCode().getCode(),
                    e);
        } catch (RuntimeException e) {
            String code = "UNEXPECTED_" + e.getClass().getSimpleName();
            return failure(candidate, runner, caseId, attempt, cold, startedAt, code, e);
        }
    }

    static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private static CallResult failure(
            Candidate candidate,
            FeatureRunner runner,
            String caseId,
            int attempt,
            boolean cold,
            long startedAt,
            String code,
            RuntimeException error) {
        String message = String.valueOf(error.getMessage());
        if (message.length() > MESSAGE_LIMIT) {
            message = message.substring(0, MESSAGE_LIMIT);
        }
        return new CallResult(
                candidate.id(),
                runner.feature(),
                caseId,
                attempt,
                false,
                elapsedMs(startedAt),
                cold,
                null,
                code,
                message,
                Map.of(),
                Map.of());
    }
}
