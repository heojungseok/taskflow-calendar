package com.taskflow.calendarsync.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.taskflow.calendarsync.outbox.CalendarOutbox;
import com.taskflow.calendarsync.outbox.CalendarOutboxRepository;
import com.taskflow.config.JpaAuditingConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class OutboxBacklogGaugeTest {

    @Autowired
    CalendarOutboxRepository repository;

    @Autowired
    TestEntityManager em;

    SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        // 최솟값을 보는 지표라 다른 테스트가 남긴 행이 끼면 안 된다. 테스트 트랜잭션이 끝나면 되돌아간다.
        em.getEntityManager().createNativeQuery("DELETE FROM calendar_outbox").executeUpdate();
        registry = new SimpleMeterRegistry();
        new OutboxBacklogGauge(repository, registry);
    }

    @Test
    void reportsZeroWhenNothingIsProcessable() {
        CalendarOutbox notYetDue = CalendarOutbox.forUpsert(940001L, "{}");
        notYetDue.markAsProcessing();
        notYetDue.markForRetry("temporary error", utcNow().plusMinutes(10));
        em.persistAndFlush(notYetDue);

        assertThat(gaugeValue()).isZero();
    }

    @Test
    void measuresPendingFromCreation() {
        CalendarOutbox pending = em.persistAndFlush(CalendarOutbox.forUpsert(940002L, "{}"));
        setTimes(pending, utcNow().minusMinutes(4), utcNow().minusMinutes(4));

        assertThat(gaugeValue()).isBetween(235.0, 250.0);
    }

    @Test
    void measuresDueRetryFromNextRetryAtNotCreation() {
        CalendarOutbox retry = CalendarOutbox.forUpsert(940003L, "{}");
        retry.markAsProcessing();
        retry.markForRetry("temporary error", utcNow().minusMinutes(1));
        em.persistAndFlush(retry);
        setTimes(retry, utcNow().minusMinutes(30), utcNow().minusMinutes(2));

        // 30분 전에 만든 건이지만 재시도 차례가 된 지는 1분이다
        assertThat(gaugeValue()).isBetween(55.0, 70.0);
    }

    @Test
    void measuresExpiredLeaseFromLeaseExpiry() {
        CalendarOutbox stuck = CalendarOutbox.forUpsert(940004L, "{}");
        stuck.markAsProcessing();
        em.persistAndFlush(stuck);
        setTimes(stuck, utcNow().minusMinutes(20), utcNow().minusMinutes(7));

        // lease 5분이 끝난 지 2분
        assertThat(gaugeValue()).isBetween(115.0, 130.0);
    }

    @Test
    void reportsNaNWhenQueryFails() {
        CalendarOutboxRepository failing = mock(CalendarOutboxRepository.class);
        when(failing.findOldestProcessableSince(any(), any(), anyInt(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(new OutboxBacklogGauge(failing, new SimpleMeterRegistry()).oldestProcessableAgeSeconds())
                .isNaN();
    }

    private double gaugeValue() {
        return registry.get("outbox_oldest_processable_age_seconds").gauge().value();
    }

    private void setTimes(CalendarOutbox outbox, LocalDateTime createdAt, LocalDateTime updatedAt) {
        em.getEntityManager()
                .createNativeQuery(
                        "UPDATE calendar_outbox SET created_at = :created, updated_at = :updated WHERE id = :id")
                .setParameter("created", createdAt)
                .setParameter("updated", updatedAt)
                .setParameter("id", outbox.getId())
                .executeUpdate();
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
