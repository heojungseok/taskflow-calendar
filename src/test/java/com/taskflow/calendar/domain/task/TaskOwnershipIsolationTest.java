package com.taskflow.calendar.domain.task;

import com.taskflow.calendar.domain.project.Project;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소유권 격리는 파생 쿼리의 프로퍼티 경로(Project_OwnerUserId)로 강제된다.
 * 경로가 잘못 엮이면 컴파일은 통과하고 격리만 조용히 풀리므로 실제 DB로 확인한다.
 * @DataJpaTest는 롤백되므로 개발 데이터에 남지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class TaskOwnershipIsolationTest {

    private static final long OWNER = 80001L;
    private static final long STRANGER = 80002L;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    TestEntityManager em;

    private Task taskOwnedBy(long ownerUserId, String title) {
        Project project = Project.of("격리 테스트 " + ownerUserId, ownerUserId);
        em.persist(project);

        Task task = Task.createTask(
                project, title, "소유권 격리 검증용 작업이다.",
                null, null, LocalDateTime.now().plusDays(1), false);
        em.persist(task);
        em.flush();
        return task;
    }

    @Test
    @DisplayName("남의 Task는 단건 조회에서 empty다 - 존재 여부를 흘리지 않는다")
    void singleLookupIsScopedToOwner() {
        Task theirs = taskOwnedBy(STRANGER, "남의 작업");

        assertThat(taskRepository.findByIdAndDeletedFalse(theirs.getId())).isPresent();
        assertThat(taskRepository
                .findByIdAndDeletedFalseAndProject_OwnerUserId(theirs.getId(), OWNER)).isEmpty();
    }

    @Test
    @DisplayName("내 Task는 그대로 조회된다")
    void ownLookupSucceeds() {
        Task mine = taskOwnedBy(OWNER, "내 작업");

        assertThat(taskRepository
                .findByIdAndDeletedFalseAndProject_OwnerUserId(mine.getId(), OWNER)).isPresent();
    }

    @Test
    @DisplayName("프로젝트 목록 조회는 남의 프로젝트 id를 넣어도 비어 있다")
    void projectListIsScopedToOwner() {
        Task theirs = taskOwnedBy(STRANGER, "남의 작업");
        Long theirProjectId = theirs.getProject().getId();

        assertThat(taskRepository
                .findAllByProjectIdAndDeletedFalseAndProject_OwnerUserId(theirProjectId, OWNER))
                .isEmpty();
        assertThat(taskRepository
                .findAllByProjectIdAndDeletedFalseAndProject_OwnerUserId(theirProjectId, STRANGER))
                .hasSize(1);
    }

    @Test
    @DisplayName("검색이 훑는 전체 조회에도 남의 Task가 섞이지 않는다")
    void globalScanIsScopedToOwner() {
        taskOwnedBy(OWNER, "내 작업");
        taskOwnedBy(STRANGER, "남의 작업");

        List<Task> mine = taskRepository.findAllByDeletedFalseAndProject_OwnerUserId(OWNER);

        assertThat(mine).isNotEmpty();
        assertThat(mine).allSatisfy(t ->
                assertThat(t.getProject().getOwnerUserId()).isEqualTo(OWNER));
    }
}
