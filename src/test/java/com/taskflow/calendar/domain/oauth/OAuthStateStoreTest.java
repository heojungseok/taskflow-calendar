package com.taskflow.calendar.domain.oauth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthStateStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @Test
    void stateIsOneTimeAndExpiresAfterTenMinutes() {
        OAuthStateStore store = new OAuthStateStore(
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10), 1_000);
        String state = store.generateState();

        assertThat(state).hasSizeGreaterThanOrEqualTo(43);
        assertThat(store.validateState(state)).isTrue();
        assertThat(store.validateState(state)).isFalse();

        MutableClock clock = new MutableClock(NOW);
        OAuthStateStore expiredStore = new OAuthStateStore(clock, Duration.ofMinutes(10), 1_000);
        String expired = expiredStore.generateState();
        clock.instant = NOW.plus(Duration.ofMinutes(10));

        assertThat(expiredStore.validateState(expired)).isFalse();
    }

    @Test
    void fullStoreRejectsNewStateWithoutEvictingValidState() {
        OAuthStateStore store = new OAuthStateStore(
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10), 1);
        String existing = store.generateState();

        assertThatThrownBy(store::generateState)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capacity");
        assertThat(store.validateState(existing)).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
