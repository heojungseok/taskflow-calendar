package com.taskflow.calendar.domain.user;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void demoUserUsesFixedExpiryAndNoSeedDataIdentity() {
        Instant expiresAt = Instant.parse("2026-08-20T12:00:00Z");

        User user = User.createDemoUser("demo-id", expiresAt);

        assertThat(user.getEmail()).isEqualTo("demo-id@demo.taskflow.local");
        assertThat(user.getName()).isEqualTo("방문자");
        assertThat(user.getProvider()).isEqualTo(Provider.DEMO);
        assertThat(user.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(user.getDemoMutationCount()).isZero();
        assertThat(user.isSessionActive(0, expiresAt.minusNanos(1))).isTrue();
        assertThat(user.isSessionActive(0, expiresAt)).isFalse();
    }

    @Test
    void invalidatingGoogleSessionsRejectsOldVersionAndAcceptsNewVersion() {
        User user = User.createGoogleUser("user@example.com", "User");
        Instant now = Instant.parse("2026-08-20T12:00:00Z");

        assertThat(user.isSessionActive(0, now)).isTrue();

        user.invalidateSessions();

        assertThat(user.isSessionActive(0, now)).isFalse();
        assertThat(user.isSessionActive(1, now)).isTrue();
    }
}
