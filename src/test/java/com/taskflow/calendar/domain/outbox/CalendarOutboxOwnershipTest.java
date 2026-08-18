package com.taskflow.calendar.domain.outbox;

import com.taskflow.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소유권 필터는 payload의 meta.requestedByUserId를 jsonb로 읽는 네이티브 쿼리다.
 * H2로는 검증되지 않으므로 실제 Postgres(docker: taskflow-postgres)에 붙는다.
 * @DataJpaTest는 각 테스트를 롤백하므로 개발 데이터에 남지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)  // createdAt/updatedAt 감사가 켜져야 insert가 통과한다
class CalendarOutboxOwnershipTest {

    private static final long OWNER = 90001L;
    private static final long STRANGER = 90002L;

    @Autowired
    CalendarOutboxRepository repository;

    @Autowired
    TestEntityManager em;

    private CalendarOutbox persist(long taskId, long requestedByUserId, OutboxStatus status) {
        String payload = """
                {"version":1,"taskId":%d,"opType":"UPSERT","meta":{"requestedByUserId":%d}}
                """.formatted(taskId, requestedByUserId);
        CalendarOutbox outbox = CalendarOutbox.forUpsert(taskId, payload);
        em.persist(outbox);
        if (status != OutboxStatus.PENDING) {
            em.flush();
            em.getEntityManager()
                    .createQuery("UPDATE CalendarOutbox o SET o.status = :s WHERE o.id = :id")
                    .setParameter("s", status)
                    .setParameter("id", outbox.getId())
                    .executeUpdate();
            em.clear();
        }
        em.flush();
        return outbox;
    }

    @Test
    @DisplayName("남의 Outbox는 목록에 나오지 않는다")
    void listExcludesOtherUsers() {
        persist(910001L, OWNER, OutboxStatus.PENDING);
        persist(910002L, STRANGER, OutboxStatus.PENDING);

        List<CalendarOutbox> mine = repository.findOwnedBy(OWNER, null, null, 100);

        assertThat(mine).isNotEmpty();
        assertThat(mine).allSatisfy(o ->
                assertThat(o.getPayload()).contains("\"requestedByUserId\":" + OWNER));
    }

    @Test
    @DisplayName("status 필터는 내 것 안에서만 적용된다")
    void listFiltersByStatusWithinOwner() {
        persist(910003L, OWNER, OutboxStatus.PENDING);
        persist(910004L, OWNER, OutboxStatus.SUCCESS);

        List<CalendarOutbox> pending = repository.findOwnedBy(OWNER, "PENDING", null, 100);

        assertThat(pending).isNotEmpty();
        assertThat(pending).allSatisfy(o -> {
            assertThat(o.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(o.getPayload()).contains("\"requestedByUserId\":" + OWNER);
        });
    }

    @Test
    @DisplayName("taskId 필터도 내 것 안에서만 적용된다")
    void listFiltersByTaskIdWithinOwner() {
        persist(910005L, OWNER, OutboxStatus.PENDING);
        persist(910006L, OWNER, OutboxStatus.PENDING);

        List<CalendarOutbox> only = repository.findOwnedBy(OWNER, null, 910005L, 100);

        assertThat(only).hasSize(1);
        assertThat(only.get(0).getTaskId()).isEqualTo(910005L);
    }

    @Test
    @DisplayName("단건 조회 - 내 것은 보인다")
    void getOwnReturnsRow() {
        CalendarOutbox mine = persist(910007L, OWNER, OutboxStatus.PENDING);

        assertThat(repository.findOwnedById(mine.getId(), OWNER)).isPresent();
    }

    @Test
    @DisplayName("단건 조회 - 남의 것은 empty (존재해도 못 본다)")
    void getOthersReturnsEmpty() {
        CalendarOutbox theirs = persist(910008L, STRANGER, OutboxStatus.PENDING);

        Optional<CalendarOutbox> asOwner = repository.findOwnedById(theirs.getId(), OWNER);

        assertThat(repository.findById(theirs.getId())).isPresent();
        assertThat(asOwner).isEmpty();
    }

    @Test
    @DisplayName("limit이 지켜진다")
    void listRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            persist(910100L + i, OWNER, OutboxStatus.PENDING);
        }

        assertThat(repository.findOwnedBy(OWNER, null, null, 3)).hasSize(3);
    }
}
