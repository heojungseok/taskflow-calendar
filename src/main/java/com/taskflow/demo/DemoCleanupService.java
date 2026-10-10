package com.taskflow.demo;

import com.taskflow.calendarsync.outbox.CalendarOutboxRepository;
import com.taskflow.identity.oauth.OAuthGoogleTokenRepository;
import com.taskflow.identity.user.Provider;
import com.taskflow.identity.user.User;
import com.taskflow.identity.user.UserRepository;
import com.taskflow.planning.project.ProjectRepository;
import com.taskflow.planning.task.TaskHistoryRepository;
import com.taskflow.planning.task.TaskRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DemoCleanupService {

    private final UserRepository userRepository;
    private final CalendarOutboxRepository outboxRepository;
    private final TaskHistoryRepository historyRepository;
    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final OAuthGoogleTokenRepository tokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cleanup(Long userId, Instant expiredBefore) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null
                || user.getProvider() != Provider.DEMO
                || user.getExpiresAt() == null
                || user.getExpiresAt().isAfter(expiredBefore)) {
            return false;
        }
        outboxRepository.lockOwnedBy(userId);
        if (outboxRepository.existsProcessingForOwner(userId)) {
            return false;
        }

        outboxRepository.deleteOwnedBy(userId);
        historyRepository.deleteOwnedBy(userId);
        taskRepository.deleteOwnedBy(userId);
        projectRepository.deleteOwnedBy(userId);
        tokenRepository.deleteByUserId(userId);
        userRepository.delete(user);
        return true;
    }
}
