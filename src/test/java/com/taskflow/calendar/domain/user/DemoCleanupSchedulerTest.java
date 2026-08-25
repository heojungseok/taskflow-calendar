package com.taskflow.calendar.domain.user;

import com.taskflow.observability.TaskFlowMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DemoCleanupSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DemoCleanupScheduler.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(DemoCleanupService.class, () -> mock(DemoCleanupService.class))
            .withBean(TaskFlowMetrics.class, () -> mock(TaskFlowMetrics.class));

    @Test
    void cleanupSchedulerCanBeDisabledForRecoveryVerification() {
        contextRunner
                .withPropertyValues("taskflow.demo.cleanup.enabled=false")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .doesNotHaveBean(DemoCleanupScheduler.class));
    }

    @Test
    void cleanupSchedulerRemainsEnabledByDefault() {
        contextRunner.run(context -> org.assertj.core.api.Assertions.assertThat(context)
                .hasSingleBean(DemoCleanupScheduler.class));
    }

    @Test
    void oneUserFailureDoesNotStopNextUser() {
        UserRepository users = mock(UserRepository.class);
        DemoCleanupService cleanup = mock(DemoCleanupService.class);
        TaskFlowMetrics metrics = mock(TaskFlowMetrics.class);
        User first = mock(User.class);
        User second = mock(User.class);
        given(first.getId()).willReturn(1L);
        given(second.getId()).willReturn(2L);
        given(users.findTop100ByProviderAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                eq(Provider.DEMO), any())).willReturn(List.of(first, second));
        doThrow(new IllegalStateException("fk")).when(cleanup).cleanup(eq(1L), any());

        new DemoCleanupScheduler(users, cleanup, metrics).cleanupExpiredUsers();

        verify(cleanup).cleanup(eq(2L), any());
    }
}
