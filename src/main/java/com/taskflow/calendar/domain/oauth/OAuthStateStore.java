package com.taskflow.calendar.domain.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth State 파라미터 관리
 * CSRF 방어를 위한 state 검증 (In-Memory 저장)
 */
@Slf4j
@Component
public class OAuthStateStore {

    private final Map<String, LocalDateTime> stateMap = new ConcurrentHashMap<>();

    /**
     * State 생성 (UUID)
     */
    public String generateState() {
        String state = UUID.randomUUID().toString();
        stateMap.put(state, LocalDateTime.now());
        log.debug("Generated OAuth state: {}", state);
        return state;
    }

    /**
     * State 검증 및 삭제 (1회용)
     * 10분 이내 생성된 state만 유효
     */
    public boolean validateState(String state) {
        LocalDateTime createdAt = stateMap.remove(state);
        if (createdAt == null) {
            log.warn("Invalid state: not found. state={}", state);
            return false;
        }

        boolean isValid = createdAt.isAfter(LocalDateTime.now().minusMinutes(10));
        if (!isValid) {
            log.warn("Expired state. state={}, createdAt={}", state, createdAt);
        }
        return isValid;
    }
}
