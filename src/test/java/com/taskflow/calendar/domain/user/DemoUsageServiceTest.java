package com.taskflow.calendar.domain.user;

import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DemoUsageServiceTest {

    @Mock UserRepository userRepository;
    @Mock ProjectRepository projectRepository;
    @Mock TaskRepository taskRepository;
    DemoUsageService service;

    @BeforeEach
    void setUp() {
        service = new DemoUsageService(userRepository, projectRepository, taskRepository);
    }

    @Test
    void googleUserIsNotCounted() {
        User user = mock(User.class);
        given(user.getProvider()).willReturn(Provider.GOOGLE);
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));

        service.beforeMutation(1L);

        verify(user, never()).incrementDemoMutationCount();
    }

    @Test
    void taskLimitIncludesDeletedTasks() {
        User user = demoUser(10);
        given(taskRepository.countByProject_OwnerUserId(1L)).willReturn(100L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.beforeTaskCreate(1L));

        assertEquals(ErrorCode.DEMO_RESOURCE_LIMIT, error.getErrorCode());
        verify(user, never()).incrementDemoMutationCount();
    }

    @Test
    void mutationLimitIsCheckedWhileUserRowIsLocked() {
        User user = demoUser(500);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.beforeMutation(1L));

        assertEquals(ErrorCode.DEMO_MUTATION_LIMIT, error.getErrorCode());
        verify(user, never()).incrementDemoMutationCount();
    }

    private User demoUser(int mutationCount) {
        User user = mock(User.class);
        given(user.getProvider()).willReturn(Provider.DEMO);
        lenient().when(user.getDemoMutationCount()).thenReturn(mutationCount);
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        return user;
    }
}
