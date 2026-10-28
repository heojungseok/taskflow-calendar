package com.taskflow.calendarsync.worker;

import com.taskflow.calendarsync.outbox.CalendarOutboxRepository;
import com.taskflow.calendarsync.outbox.OutboxPolicy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 처리 가능한 Outbox가 처리되지 않고 기다린 최장 시간(초).
 *
 * <p>Prometheus가 지표를 가져갈 때마다 DB를 직접 조회해 값을 만든다. 예전에는 Worker가 돌 때 값을 넣어서,
 * Worker나 스케줄러 스레드가 멈추면 값도 멈춰 backlog 알림이 울리지 않았다.
 */
@Component
@Slf4j
public class OutboxBacklogGauge {

    private final CalendarOutboxRepository outboxRepository;

    public OutboxBacklogGauge(CalendarOutboxRepository outboxRepository, MeterRegistry registry) {
        this.outboxRepository = outboxRepository;
        Gauge.builder("outbox_oldest_processable_age_seconds", this, OutboxBacklogGauge::oldestProcessableAgeSeconds)
                .register(registry);
    }

    double oldestProcessableAgeSeconds() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int leaseMinutes = OutboxPolicy.LEASE_TIMEOUT_MINUTES.value();
        try {
            LocalDateTime since = outboxRepository.findOldestProcessableSince(
                    now, now.minusMinutes(leaseMinutes), leaseMinutes, OutboxPolicy.MAX_RETRY.value());
            return since == null ? 0 : Math.max(0, Duration.between(since, now).toSeconds());
        } catch (RuntimeException e) {
            // 값을 모르면 0이 아니라 NaN으로 내보낸다. 0은 '대기 없음'으로 읽혀 장애를 가린다.
            log.warn(
                    "Outbox backlog gauge query failed. errorType={}",
                    e.getClass().getSimpleName());
            return Double.NaN;
        }
    }
}
