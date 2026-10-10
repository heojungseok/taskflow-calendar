package com.taskflow.calendar.domain.project;

import com.taskflow.calendar.domain.task.TaskRepository;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;
import com.taskflow.identity.user.Provider;
import com.taskflow.identity.user.User;
import com.taskflow.identity.user.UserRepository;
import com.taskflow.identity.user.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DemoUsageService {

    private static final int PROJECT_LIMIT = 10;
    private static final int TASK_LIMIT = 100;
    private static final int MUTATION_LIMIT = 500;

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;

    @Transactional
    public void beforeProjectCreate(Long userId) {
        User user = lockDemoUser(userId);
        if (user != null && projectRepository.countByOwnerUserId(userId) >= PROJECT_LIMIT) {
            throw new BusinessException(ErrorCode.DEMO_RESOURCE_LIMIT);
        }
        increment(user);
    }

    @Transactional
    public boolean beforeTaskCreate(Long userId) {
        User user = lockDemoUser(userId);
        if (user != null && taskRepository.countByProject_OwnerUserId(userId) >= TASK_LIMIT) {
            throw new BusinessException(ErrorCode.DEMO_RESOURCE_LIMIT);
        }
        increment(user);
        return user != null;
    }

    @Transactional
    public void beforeMutation(Long userId) {
        increment(lockDemoUser(userId));
    }

    private User lockDemoUser(Long userId) {
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> new UserNotFoundException(userId));
        return user.getProvider() == Provider.DEMO ? user : null;
    }

    private void increment(User user) {
        if (user == null) {
            return;
        }
        if (user.getDemoMutationCount() >= MUTATION_LIMIT) {
            throw new BusinessException(ErrorCode.DEMO_MUTATION_LIMIT);
        }
        user.incrementDemoMutationCount();
    }
}
