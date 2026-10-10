package com.taskflow.calendarsync.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.taskflow.identity.user.User;
import com.taskflow.identity.user.UserRepository;
import com.taskflow.planning.project.Project;
import com.taskflow.planning.project.ProjectRepository;
import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskHistoryRepository;
import com.taskflow.planning.task.TaskRepository;
import com.taskflow.planning.task.TaskService;
import com.taskflow.planning.task.dto.CreateTaskRequest;
import com.taskflow.planning.task.dto.TaskResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * planning이 이벤트만 발행하고 calendarsync가 Outbox를 적재하는 구조에서도 Transactional Outbox가 유지되는지 실제 DB로 확인한다. Task와
 * Outbox는 함께 커밋되고, 적재가 실패하면 Task도 남지 않아야 한다.
 */
@SpringBootTest(properties = "outbox.worker.enabled=false")
class TaskCalendarSyncTransactionTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskHistoryRepository historyRepository;

    @Autowired
    private CalendarOutboxRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private CalendarOutboxService calendarOutboxService;

    private Long userId;
    private Long projectId;

    @BeforeEach
    void signIn() {
        userId = userRepository
                .saveAndFlush(User.createGoogleUser("outbox-tx@example.test", "outbox-tx"))
                .getId();
        projectId = projectRepository
                .saveAndFlush(Project.of("Outbox 트랜잭션", userId))
                .getId();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        // 소유자 기준 일괄 삭제는 @Modifying 쿼리라 트랜잭션 안에서 실행해야 한다.
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            outboxRepository.deleteOwnedBy(userId);
            historyRepository.deleteOwnedBy(userId);
            taskRepository.deleteOwnedBy(userId);
            projectRepository.deleteOwnedBy(userId);
            userRepository.deleteById(userId);
        });
    }

    @Test
    void Task_생성이_커밋되면_UPSERT_Outbox도_함께_커밋된다() {
        TaskResponse created = taskService.createTask(projectId, syncedTask("함께 커밋"));

        assertThat(outboxRepository.findTopByTaskIdOrderByCreatedAtDesc(created.getId()))
                .hasValueSatisfying(outbox -> {
                    assertThat(outbox.getOpType()).isEqualTo(OutboxOpType.UPSERT);
                    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
                });
    }

    @Test
    void Outbox_적재가_실패하면_Task_생성도_롤백된다() {
        doThrow(new IllegalStateException("outbox down"))
                .when(calendarOutboxService)
                .enqueueUpsert(any(Task.class));

        assertThatThrownBy(() -> taskService.createTask(projectId, syncedTask("롤백 대상")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(taskRepository.findAllByProjectIdAndDeletedFalse(projectId)).isEmpty();
    }

    private static CreateTaskRequest syncedTask(String title) {
        LocalDateTime dueAt = LocalDateTime.of(2026, 10, 20, 18, 0);
        return new CreateTaskRequest(title, null, null, dueAt.minusHours(1), dueAt, true);
    }
}
