package com.taskflow.identity.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DemoUsageServiceTest {

    @Mock
    UserRepository userRepository;

    DemoUsageService service;

    @BeforeEach
    void setUp() {
        service = new DemoUsageService(userRepository);
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
    void createLimitIsCountedAfterUserRowIsLocked() {
        User user = demoUser(10);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.beforeCreate(
                        1L,
                        () -> {
                            // 개수는 사용자 행 잠금 뒤에 읽어야 동시 생성이 한도를 넘지 못한다
                            verify(userRepository).findByIdForUpdate(1L);
                            return 100L;
                        },
                        100));

        assertEquals(ErrorCode.DEMO_RESOURCE_LIMIT, error.getErrorCode());
        verify(user, never()).incrementDemoMutationCount();
    }

    @Test
    void mutationLimitIsCheckedWhileUserRowIsLocked() {
        User user = demoUser(500);

        BusinessException error = assertThrows(BusinessException.class, () -> service.beforeMutation(1L));

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
