package com.taskflow.calendar.domain.user;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import com.taskflow.observability.TaskFlowMetrics;

class DemoCleanupSchedulerTest {

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
