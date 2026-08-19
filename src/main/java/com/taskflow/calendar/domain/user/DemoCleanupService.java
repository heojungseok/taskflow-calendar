package com.taskflow.calendar.domain.user;

import com.taskflow.calendar.domain.oauth.OAuthGoogleTokenRepository;
import com.taskflow.calendar.domain.outbox.CalendarOutboxRepository;
import com.taskflow.calendar.domain.project.ProjectRepository;
import com.taskflow.calendar.domain.task.TaskHistoryRepository;
import com.taskflow.calendar.domain.task.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
    public boolean cleanup(Long userId, LocalDateTime expiredBefore) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.getProvider() != Provider.DEMO
                || user.getExpiresAt() == null || user.getExpiresAt().isAfter(expiredBefore)) {
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
