package com.taskflow.calendar.domain.user;

import com.taskflow.security.JwtTokenProvider;
import com.taskflow.service.AuthService;
import com.taskflow.web.dto.auth.AuthSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "outbox.worker.enabled=false")
class DemoExpiryIntegrationTest {

    @Autowired AuthService authService;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider jwtTokenProvider;

    private Long userId;

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            users.deleteById(userId);
        }
    }

    @Test
    void persistedDemoJwtAndResponseShareOneExpiryInstant() {
        AuthSession session = authService.createDemoSession();
        userId = session.userId();
        User persisted = users.findById(userId).orElseThrow();
        Instant expiresAt = session.expiresAt();

        assertThat(expiresAt.getNano()).isZero();
        assertThat(persisted.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(jwtTokenProvider.getExpiration(session.token())).isEqualTo(expiresAt);
        assertThat(persisted.isSessionActive(0, expiresAt.minusNanos(1))).isTrue();
        assertThat(persisted.isSessionActive(0, expiresAt)).isFalse();
        assertThat(users.findTop100ByProviderAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                Provider.DEMO, expiresAt.minusSeconds(1)))
                .extracting(User::getId)
                .doesNotContain(userId);
        assertThat(users.findTop100ByProviderAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                Provider.DEMO, expiresAt))
                .extracting(User::getId)
                .contains(userId);
    }
}
