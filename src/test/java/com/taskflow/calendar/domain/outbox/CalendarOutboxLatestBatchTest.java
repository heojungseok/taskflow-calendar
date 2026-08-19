package com.taskflow.calendar.domain.outbox;

import com.taskflow.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findLatestByTaskIdIn은 Postgres 전용 DISTINCT ON을 쓰는 네이티브 쿼리다.
 * H2에는 DISTINCT ON이 없어 실제 Postgres(docker: taskflow-postgres)에 붙는다.
 *
 * 이 테스트가 지키는 것: taskId당 정확히 1건이고, 그 1건이 단건 조회
 * (findTopByTaskIdOrderByCreatedAtDesc)와 같은 행이어야 한다. 두 경로가 갈리면
 * 목록의 동기화 점과 상세 화면이 서로 다른 상태를 보여준다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class CalendarOutboxLatestBatchTest {

    @Autowired
    CalendarOutboxRepository repository;

    @Autowired
    TestEntityManager em;

    private CalendarOutbox persist(long taskId) {
        CalendarOutbox outbox = CalendarOutbox.forUpsert(
                taskId, "{\"version\":1,\"taskId\":" + taskId + "}");
        em.persist(outbox);
        em.flush();
        return outbox;
    }

    /** created_at은 감사(auditing)가 채우므로 순서를 만들려면 직접 덮어쓴다. */
    private void setCreatedAt(CalendarOutbox outbox, LocalDateTime createdAt) {
        em.getEntityManager()
                .createNativeQuery("UPDATE calendar_outbox SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", outbox.getId())
                .executeUpdate();
        em.clear();
    }

    @Test
    @DisplayName("taskId당 최신 1건만 돌려준다")
    void returnsOnlyLatestPerTask() {
        long taskId = 920001L;
        CalendarOutbox old = persist(taskId);
        CalendarOutbox latest = persist(taskId);
        setCreatedAt(old, LocalDateTime.now().minusHours(2));
        setCreatedAt(latest, LocalDateTime.now().minusMinutes(1));

        List<CalendarOutbox> found = repository.findLatestByTaskIdIn(List.of(taskId));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(latest.getId());
    }

    @Test
    @DisplayName("created_at이 같으면 id가 큰 쪽을 고른다 — 순서가 흔들리지 않는다")
    void breaksTieById() {
        long taskId = 920002L;
        LocalDateTime sameMoment = LocalDateTime.now().minusMinutes(5);
        CalendarOutbox first = persist(taskId);
        CalendarOutbox second = persist(taskId);
        setCreatedAt(first, sameMoment);
        setCreatedAt(second, sameMoment);

        List<CalendarOutbox> found = repository.findLatestByTaskIdIn(List.of(taskId));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(Math.max(first.getId(), second.getId()));
    }

    @Test
    @DisplayName("여러 Task를 섞어 넣어도 각각의 최신을 돌려준다")
    void resolvesEachTaskIndependently() {
        long taskA = 920003L;
        long taskB = 920004L;
        CalendarOutbox oldA = persist(taskA);
        CalendarOutbox latestA = persist(taskA);
        CalendarOutbox onlyB = persist(taskB);
        setCreatedAt(oldA, LocalDateTime.now().minusDays(1));
        setCreatedAt(latestA, LocalDateTime.now().minusMinutes(3));
        setCreatedAt(onlyB, LocalDateTime.now().minusMinutes(2));

        Map<Long, CalendarOutbox> byTaskId = repository.findLatestByTaskIdIn(List.of(taskA, taskB)).stream()
                .collect(Collectors.toMap(CalendarOutbox::getTaskId, Function.identity()));

        assertThat(byTaskId).hasSize(2);
        assertThat(byTaskId.get(taskA).getId()).isEqualTo(latestA.getId());
        assertThat(byTaskId.get(taskB).getId()).isEqualTo(onlyB.getId());
    }

    @Test
    @DisplayName("Outbox가 없는 taskId는 결과에서 빠진다 — 없는 것을 만들어내지 않는다")
    void omitsTasksWithoutOutbox() {
        long withOutbox = 920005L;
        long withoutOutbox = 920006L;
        persist(withOutbox);

        List<CalendarOutbox> found = repository.findLatestByTaskIdIn(List.of(withOutbox, withoutOutbox));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getTaskId()).isEqualTo(withOutbox);
    }

    @Test
    @DisplayName("배치 조회와 단건 조회가 같은 행을 가리킨다")
    void agreesWithSingleLookup() {
        long taskId = 920007L;
        CalendarOutbox old = persist(taskId);
        CalendarOutbox latest = persist(taskId);
        setCreatedAt(old, LocalDateTime.now().minusHours(3));
        setCreatedAt(latest, LocalDateTime.now().minusMinutes(4));

        CalendarOutbox batch = repository.findLatestByTaskIdIn(List.of(taskId)).get(0);
        CalendarOutbox single = repository.findTopByTaskIdOrderByCreatedAtDesc(taskId).orElseThrow();

        assertThat(batch.getId()).isEqualTo(single.getId());
    }
}
