package com.taskflow.calendar.domain.user;

import com.taskflow.calendar.domain.oauth.OAuthGoogleTokenRepository;
import com.taskflow.calendar.domain.outbox.CalendarOutboxRepository;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.task.TaskHistoryRepository;
import com.taskflow.calendar.domain.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DemoCleanupServiceTest {

    @Mock UserRepository userRepository;
    @Mock CalendarOutboxRepository outboxRepository;
    @Mock TaskHistoryRepository historyRepository;
    @Mock TaskRepository taskRepository;
    @Mock ProjectRepository projectRepository;
    @Mock OAuthGoogleTokenRepository tokenRepository;
    DemoCleanupService service;
    Instant threshold;
    User user;

    @BeforeEach
    void setUp() {
        service = new DemoCleanupService(userRepository, outboxRepository, historyRepository,
                taskRepository, projectRepository, tokenRepository);
        threshold = Instant.parse("2026-08-19T12:00:00Z");
        user = mock(User.class);
        given(user.getProvider()).willReturn(Provider.DEMO);
        given(user.getExpiresAt()).willReturn(threshold.minusSeconds(1));
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
    }

    @Test
    void processingOutboxDefersOnlyThatUser() {
        given(outboxRepository.existsProcessingForOwner(1L)).willReturn(true);

        assertFalse(service.cleanup(1L, threshold));

        verify(taskRepository, never()).deleteOwnedBy(1L);
        verify(userRepository, never()).delete(user);
    }

    @Test
    void expiredDemoIsDeletedInForeignKeyOrder() {
        assertTrue(service.cleanup(1L, threshold));

        InOrder order = inOrder(outboxRepository, historyRepository, taskRepository,
                projectRepository, tokenRepository, userRepository);
        order.verify(outboxRepository).deleteOwnedBy(1L);
        order.verify(historyRepository).deleteOwnedBy(1L);
        order.verify(taskRepository).deleteOwnedBy(1L);
        order.verify(projectRepository).deleteOwnedBy(1L);
        order.verify(tokenRepository).deleteByUserId(1L);
        order.verify(userRepository).delete(user);
    }
}
