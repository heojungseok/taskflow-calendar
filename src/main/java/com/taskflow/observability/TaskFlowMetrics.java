package com.taskflow.observability;

import com.taskflow.common.exception.BusinessException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class TaskFlowMetrics {

    private final MeterRegistry registry;
    private final Counter demoSessionsStarted;
    private final Counter demoUsersExpired;
    private final Counter demoCleanupFailures;
    private final Counter demoTasksCreated;
    private final AtomicLong oldestExpiredAgeSeconds = new AtomicLong();
    private final AtomicLong oldestProcessableAgeSeconds = new AtomicLong();
    private final AtomicLong googleUsersRegistered = new AtomicLong(-1);
    private final AtomicLong googleUsersCreated24h = new AtomicLong(-1);
    private final AtomicLong demoSessionsActive = new AtomicLong(-1);

    public TaskFlowMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.demoSessionsStarted = registry.counter("demo_sessions_started_total");
        this.demoUsersExpired = registry.counter("demo_users_expired_total");
        this.demoCleanupFailures = registry.counter("demo_cleanup_failures_total");
        this.demoTasksCreated = registry.counter("demo_task_creations_total");
        Gauge.builder("demo_oldest_expired_age_seconds", oldestExpiredAgeSeconds, AtomicLong::get)
                .register(registry);
        Gauge.builder("outbox_oldest_processable_age_seconds", oldestProcessableAgeSeconds, AtomicLong::get)
                .register(registry);
        Gauge.builder("taskflow_google_users_registered", googleUsersRegistered,
                        value -> value.get() < 0 ? Double.NaN : value.get())
                .register(registry);
        Gauge.builder("taskflow_google_users_created_24h", googleUsersCreated24h,
                        value -> value.get() < 0 ? Double.NaN : value.get())
                .register(registry);
        Gauge.builder("taskflow_demo_sessions_active", demoSessionsActive,
                        value -> value.get() < 0 ? Double.NaN : value.get())
                .register(registry);
    }

    public void demoSessionStarted() { demoSessionsStarted.increment(); }
    public void demoUserExpired() { demoUsersExpired.increment(); }
    public void demoCleanupFailed() { demoCleanupFailures.increment(); }
    public void demoTaskCreated() { demoTasksCreated.increment(); }

    public void outboxProcessed(String outcome, String reason) {
        registry.counter("outbox_processed_total", "outcome", outcome, "reason", reason).increment();
    }

    public <T> T observeGeminiCall(String feature, Supplier<T> call) {
        long startedAt = System.nanoTime();
        String outcome = "success";
        String errorCode = "none";
        try {
            return call.get();
        } catch (BusinessException e) {
            outcome = "failure";
            errorCode = e.getErrorCode().getCode();
            throw e;
        } catch (RuntimeException e) {
            outcome = "failure";
            errorCode = "UNCLASSIFIED";
            throw e;
        } finally {
            registry.counter("gemini_calls_total",
                    "feature", feature,
                    "outcome", outcome,
                    "error_code", errorCode).increment();
            registry.timer("gemini_calls", "feature", feature)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    public void setOldestExpiredAgeSeconds(long seconds) {
        oldestExpiredAgeSeconds.set(Math.max(0, seconds));
    }

    public void setOldestProcessableAgeSeconds(long seconds) {
        oldestProcessableAgeSeconds.set(Math.max(0, seconds));
    }

    public void setUserCounts(long googleRegistered, long googleCreated24h, long demoActive) {
        googleUsersRegistered.set(Math.max(0, googleRegistered));
        googleUsersCreated24h.set(Math.max(0, googleCreated24h));
        demoSessionsActive.set(Math.max(0, demoActive));
    }
}
