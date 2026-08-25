package com.taskflow.calendar.domain.user;

import com.taskflow.calendar.domain.oauth.OAuthGoogleTokenRepository;
import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.CalendarOutboxRepository;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskChangeType;
import com.taskflow.calendar.domain.task.TaskHistory;
import com.taskflow.calendar.domain.task.TaskHistoryRepository;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class DemoCleanupRepositoryTest {

    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired TaskRepository tasks;
    @Autowired TaskHistoryRepository histories;
    @Autowired CalendarOutboxRepository outboxes;
    @Autowired OAuthGoogleTokenRepository tokens;
    @Autowired TestEntityManager em;

    @Test
    void cleanupQueriesRemoveOwnedGraphInOneTransaction() {
        Instant threshold = Instant.now();
        User user = users.save(User.createDemoUser("cleanup-repository", threshold.minusSeconds(60)));
        Project project = projects.save(Project.of("cleanup", user.getId()));
        Task task = tasks.save(Task.createTask(project, "cleanup", null, user,
                null, null, false));
        histories.save(TaskHistory.builder()
                .task(task)
                .changedByUser(user)
                .changeType(TaskChangeType.CONTENT)
                .afterValue("created")
                .build());
        outboxes.save(CalendarOutbox.forUpsert(task.getId(), "{}"));
        em.flush();
        em.clear();

        DemoCleanupService service = new DemoCleanupService(
                users, outboxes, histories, tasks, projects, tokens);
        assertThat(service.cleanup(user.getId(), threshold)).isTrue();
        users.flush();
        em.clear();

        assertThat(users.findById(user.getId())).isEmpty();
        assertThat(projects.findById(project.getId())).isEmpty();
        assertThat(tasks.findById(task.getId())).isEmpty();
        assertThat(outboxes.findAll()).noneMatch(outbox -> outbox.getTaskId().equals(task.getId()));
    }
}
