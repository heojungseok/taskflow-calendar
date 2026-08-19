package com.taskflow.calendar.domain.task;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.task.dto.TaskResponse;
import com.taskflow.calendar.domain.user.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResponseTest {

    @Test
    void foreignLegacyAssigneeIsNotExposed() throws Exception {
        User foreign = User.createGoogleUser("foreign@example.test", "foreign");
        var id = User.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(foreign, 2L);
        Task task = Task.createTask(Project.of("owned", 1L), "task", null,
                foreign, null, null, false);

        TaskResponse response = TaskResponse.from(task);

        assertThat(response.getAssigneeUserId()).isNull();
        assertThat(response.getAssigneeName()).isNull();
    }
}
