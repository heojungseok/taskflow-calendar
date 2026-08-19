package com.taskflow.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT는 로그인의 유일한 근거인데 이 클래스를 검증하는 테스트가 없었다.
 * 유일하게 등장하는 OutboxControllerSecurityTest는 @MockitoBean으로 통째 대체하므로
 * JJWT 실제 경로는 한 줄도 타지 않는다.
 *
 * Spring 없이 생성자만으로 만들 수 있어 순수 단위 테스트로 둔다.
 */
class JwtTokenProviderTest {

    /** HS256은 키가 256비트 이상이어야 한다. 32자 = 256비트. */
    private static final String SECRET = "test-secret-key-for-jwt-provider-0123456789";
    private static final String OTHER_SECRET = "another-secret-key-entirely-9876543210-abc";
    private static final long ONE_DAY_MS = 86_400_000L;
    private static final Long USER_ID = 7L;

    private JwtTokenProvider provider(String secret, long expirationMs) {
        return new JwtTokenProvider(secret, expirationMs);
    }

    @Test
    @DisplayName("발급한 토큰은 검증을 통과하고 같은 userId를 돌려준다")
    void roundTrip() {
        JwtTokenProvider provider = provider(SECRET, ONE_DAY_MS);

        String token = provider.generateToken(USER_ID);

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUserIdFromToken(token)).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("만료된 토큰은 통과하지 못한다")
    void expiredTokenIsRejected() {
        // 발급 시점에 이미 만료된 토큰
        JwtTokenProvider provider = provider(SECRET, -1_000L);

        String token = provider.generateToken(USER_ID);

        assertThat(provider.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("다른 시크릿으로 발급한 토큰은 통과하지 못한다")
    void tokenFromAnotherSecretIsRejected() {
        String foreignToken = provider(OTHER_SECRET, ONE_DAY_MS).generateToken(USER_ID);

        assertThat(provider(SECRET, ONE_DAY_MS).validateToken(foreignToken)).isFalse();
    }

    @Test
    @DisplayName("서명이 변조된 토큰은 통과하지 못한다")
    void tamperedTokenIsRejected() {
        JwtTokenProvider provider = provider(SECRET, ONE_DAY_MS);
        String token = provider.generateToken(USER_ID);

        // payload 한 글자를 바꾸면 서명이 어긋난다
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'e' ? 'f' : 'e';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("형식이 아닌 값도 예외 대신 false로 떨어진다")
    void malformedInputIsRejected() {
        JwtTokenProvider provider = provider(SECRET, ONE_DAY_MS);

        assertThat(provider.validateToken("not-a-jwt")).isFalse();
        assertThat(provider.validateToken("")).isFalse();
        assertThat(provider.validateToken(null)).isFalse();
    }

    @Test
    @DisplayName("검증을 건너뛰고 userId를 꺼내려 하면 예외가 난다 — 조용히 통과시키지 않는다")
    void getUserIdThrowsOnInvalidToken() {
        JwtTokenProvider provider = provider(SECRET, ONE_DAY_MS);
        String foreignToken = provider(OTHER_SECRET, ONE_DAY_MS).generateToken(USER_ID);

        assertThatThrownBy(() -> provider.getUserIdFromToken(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("시크릿이 256비트 미만이면 생성 자체가 실패한다")
    void shortSecretFailsFast() {
        // JWT_SECRET이 환경변수가 된 뒤(§15) 짧은 값을 넣으면 여기서 기동이 죽는다.
        // 배포 때 원인을 빨리 찾으라고 남기는 테스트다.
        assertThatThrownBy(() -> provider("too-short", ONE_DAY_MS))
                .isInstanceOf(io.jsonwebtoken.security.WeakKeyException.class);
    }
}
