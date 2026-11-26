package com.taskflow.assistant.llm.bakeoff;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class ConcurrencyProbe {

    private ConcurrencyProbe() {}

    static ConcurrencyResult run(Candidate candidate, SearchRunner searchRunner, int requests) throws Exception {
        CallExecutor.execute(candidate, searchRunner, 0, 0);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CallResult>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                int caseIndex = i % searchRunner.caseIds().size();
                futures.add(pool.submit(() -> {
                    start.await();
                    return CallExecutor.execute(candidate, searchRunner, caseIndex, 0);
                }));
            }
            long startedAt = System.nanoTime();
            start.countDown();
            List<Long> latencies = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            int successes = 0;
            for (Future<CallResult> future : futures) {
                CallResult result = future.get();
                latencies.add(result.latencyMs());
                if (result.ok()) {
                    successes++;
                } else {
                    errors.add(result.errorCode());
                }
            }
            return new ConcurrencyResult(
                    candidate.id(), requests, successes, CallExecutor.elapsedMs(startedAt), latencies, errors);
        } finally {
            pool.shutdownNow();
        }
    }
}
