package com.taskflow.calendarsync.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskflow.config.JpaAuditingConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class CalendarOutboxProcessableTest {

    @Autowired
    CalendarOutboxRepository repository;

    @Autowired
    TestEntityManager em;

    @Test
    void excludesTerminalFailureButKeepsDueRetry() {
        CalendarOutbox terminal = CalendarOutbox.forUpsert(930001L, "{}");
        terminal.markAsProcessing();
        terminal.markAsFailed("insufficient scopes");
        em.persist(terminal);

        CalendarOutbox retryable = CalendarOutbox.forUpsert(930002L, "{}");
        retryable.markAsProcessing();
        retryable.markForRetry("temporary error", LocalDateTime.now().minusMinutes(1));
        em.persist(retryable);
        em.flush();

        List<CalendarOutbox> found = repository.findProcessable(
                LocalDateTime.now(), LocalDateTime.now().minusMinutes(5), 5);

        assertThat(found)
                .extracting(CalendarOutbox::getId)
                .contains(retryable.getId())
                .doesNotContain(terminal.getId());
        assertThat(repository.claimForProcessing(
                        terminal.getId(),
                        LocalDateTime.now(),
                        LocalDateTime.now().minusMinutes(5)))
                .isZero();
        assertThat(repository.claimForProcessing(
                        retryable.getId(),
                        LocalDateTime.now(),
                        LocalDateTime.now().minusMinutes(5)))
                .isOne();
    }
}
