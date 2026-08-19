package com.taskflow.calendar.domain.oauth;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth State 파라미터 관리
 * CSRF 방어를 위한 state 검증 (In-Memory 저장)
 */
@Component
public class OAuthStateStore {

    private static final int STATE_BYTES = 32;
    private final Map<String, Instant> stateMap = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;
    private final int capacity;
    private Clock clock;

    public OAuthStateStore() {
        this(Clock.systemUTC(), Duration.ofMinutes(10), 1_000);
    }

    OAuthStateStore(Clock clock, Duration ttl, int capacity) {
        this.clock = clock;
        this.ttl = ttl;
        this.capacity = capacity;
    }

    /**
     * State 생성 (UUID)
     */
    public synchronized String generateState() {
        Instant now = clock.instant();
        stateMap.entrySet().removeIf(entry -> !entry.getValue().plus(ttl).isAfter(now));
        if (stateMap.size() >= capacity) {
            throw new IllegalStateException("OAuth state capacity exceeded");
        }

        byte[] bytes = new byte[STATE_BYTES];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        stateMap.put(state, now);
        return state;
    }

    /**
     * State 검증 및 삭제 (1회용)
     * 10분 이내 생성된 state만 유효
     */
    public boolean validateState(String state) {
        Instant createdAt = stateMap.remove(state);
        if (createdAt == null) {
            return false;
        }
        return createdAt.plus(ttl).isAfter(clock.instant());
    }
}
