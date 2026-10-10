package com.taskflow.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskflow.identity.security.AuthSession;
import com.taskflow.identity.security.JwtTokenProvider;
import com.taskflow.identity.user.Provider;
import com.taskflow.identity.user.User;
import com.taskflow.identity.user.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "outbox.worker.enabled=false")
class DemoExpiryIntegrationTest {

    @Autowired
    AuthService authService;

    @Autowired
    UserRepository users;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

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
        assertThat(users.findTop100ByProviderAndExpiresAtLessThanEqualOrderByExpiresAtAsc(Provider.DEMO, expiresAt))
                .extracting(User::getId)
                .contains(userId);
    }
}
