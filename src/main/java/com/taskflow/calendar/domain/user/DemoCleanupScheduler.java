package com.taskflow.calendar.domain.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DemoCleanupScheduler {

    private final UserRepository userRepository;
    private final DemoCleanupService cleanupService;

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanupExpiredUsers() {
        LocalDateTime expiredBefore = LocalDateTime.now().minusMinutes(1);
        for (User user : userRepository
                .findTop100ByProviderAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                        Provider.DEMO, expiredBefore)) {
            try {
                cleanupService.cleanup(user.getId(), expiredBefore);
            } catch (RuntimeException e) {
                log.error("Demo cleanup failed. errorType={}", e.getClass().getSimpleName());
            }
        }
    }
}
