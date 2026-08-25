package com.taskflow.calendar.domain.oauth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.taskflow.calendar.domain.oauth.OAuthStateStore.OAuthAttempt.CONSENT_RETRY;
import static com.taskflow.calendar.domain.oauth.OAuthStateStore.OAuthAttempt.NORMAL;

class OAuthStateStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @Test
    void stateIsOneTimeAndExpiresAfterTenMinutes() {
        OAuthStateStore store = new OAuthStateStore(
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10), 1_000);
        String state = store.generateState(NORMAL);

        assertThat(state).hasSizeGreaterThanOrEqualTo(43);
        assertThat(store.consumeState(state)).contains(NORMAL);
        assertThat(store.consumeState(state)).isEmpty();

        MutableClock clock = new MutableClock(NOW);
        OAuthStateStore expiredStore = new OAuthStateStore(clock, Duration.ofMinutes(10), 1_000);
        String expired = expiredStore.generateState(CONSENT_RETRY);
        clock.instant = NOW.plus(Duration.ofMinutes(10));

        assertThat(expiredStore.consumeState(expired)).isEmpty();
    }

    @Test
    void stateCarriesTheServerAuthorizedOAuthAttempt() {
        OAuthStateStore store = new OAuthStateStore(
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10), 1_000);

        String normal = store.generateState(NORMAL);
        String retry = store.generateState(CONSENT_RETRY);

        assertThat(store.consumeState(normal)).contains(NORMAL);
        assertThat(store.consumeState(retry)).contains(CONSENT_RETRY);
    }

    @Test
    void fullStoreRejectsNewStateWithoutEvictingValidState() {
        OAuthStateStore store = new OAuthStateStore(
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10), 1);
        String existing = store.generateState(NORMAL);

        assertThatThrownBy(() -> store.generateState(CONSENT_RETRY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capacity");
        assertThat(store.consumeState(existing)).contains(NORMAL);
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
