package com.taskflow.calendar.domain.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.taskflow.observability.TaskFlowMetrics;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "taskflow.demo.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DemoCleanupScheduler {

    private final UserRepository userRepository;
    private final DemoCleanupService cleanupService;
    private final TaskFlowMetrics metrics;

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanupExpiredUsers() {
        Instant now = Instant.now();
        Instant expiredBefore = now.minusSeconds(60);
        List<User> expired = userRepository
                .findTop100ByProviderAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                        Provider.DEMO, expiredBefore);
        Instant oldest = expired.isEmpty() ? null : expired.get(0).getExpiresAt();
        metrics.setOldestExpiredAgeSeconds(oldest == null ? 0 : Duration.between(oldest, now).getSeconds());
        for (User user : expired) {
            try {
                if (cleanupService.cleanup(user.getId(), expiredBefore)) {
                    metrics.demoUserExpired();
                }
            } catch (RuntimeException e) {
                metrics.demoCleanupFailed();
                log.error("Demo cleanup failed. errorType={}", e.getClass().getSimpleName());
            }
        }
    }
}
