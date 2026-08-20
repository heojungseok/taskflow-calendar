package com.taskflow.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class TaskFlowMetrics {

    private final MeterRegistry registry;
    private final Counter demoSessionsStarted;
    private final Counter demoUsersExpired;
    private final Counter demoCleanupFailures;
    private final Counter demoTasksCreated;
    private final AtomicLong oldestExpiredAgeSeconds = new AtomicLong();
    private final AtomicLong oldestProcessableAgeSeconds = new AtomicLong();

    public TaskFlowMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.demoSessionsStarted = registry.counter("demo_sessions_started_total");
        this.demoUsersExpired = registry.counter("demo_users_expired_total");
        this.demoCleanupFailures = registry.counter("demo_cleanup_failures_total");
        this.demoTasksCreated = registry.counter("demo_tasks_created_total");
        Gauge.builder("demo_oldest_expired_age_seconds", oldestExpiredAgeSeconds, AtomicLong::get)
                .register(registry);
        Gauge.builder("outbox_oldest_processable_age_seconds", oldestProcessableAgeSeconds, AtomicLong::get)
                .register(registry);
    }

    public void demoSessionStarted() { demoSessionsStarted.increment(); }
    public void demoUserExpired() { demoUsersExpired.increment(); }
    public void demoCleanupFailed() { demoCleanupFailures.increment(); }
    public void demoTaskCreated() { demoTasksCreated.increment(); }

    public void outboxProcessed(String outcome, String reason) {
        registry.counter("outbox_processed_total", "outcome", outcome, "reason", reason).increment();
    }

    public void setOldestExpiredAgeSeconds(long seconds) {
        oldestExpiredAgeSeconds.set(Math.max(0, seconds));
    }

    public void setOldestProcessableAgeSeconds(long seconds) {
        oldestProcessableAgeSeconds.set(Math.max(0, seconds));
    }
}
