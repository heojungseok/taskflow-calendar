package com.taskflow.calendar.domain.user;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void demoUserUsesFixedExpiryAndNoSeedDataIdentity() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 8, 20, 12, 0);

        User user = User.createDemoUser("demo-id", expiresAt);

        assertThat(user.getEmail()).isEqualTo("demo-id@demo.taskflow.local");
        assertThat(user.getName()).isEqualTo("방문자");
        assertThat(user.getProvider()).isEqualTo(Provider.DEMO);
        assertThat(user.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(user.getDemoMutationCount()).isZero();
        assertThat(user.isSessionActive(expiresAt.minusNanos(1))).isTrue();
        assertThat(user.isSessionActive(expiresAt)).isFalse();
    }
}
