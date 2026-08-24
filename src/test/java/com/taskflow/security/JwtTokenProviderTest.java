package com.taskflow.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

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
    @DisplayName("발급한 토큰은 검증을 통과하고 같은 userId와 세션 버전을 돌려준다")
    void roundTrip() {
        JwtTokenProvider provider = provider(SECRET, ONE_DAY_MS);

        String token = provider.generateToken(USER_ID, 3);

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUserIdFromToken(token)).isEqualTo(USER_ID);
        assertThat(provider.getSessionVersion(token)).isEqualTo(3);
    }

    @Test
    @DisplayName("지정한 만료 시각을 그대로 사용한다")
    void exactExpiry() {
        JwtTokenProvider provider = provider(SECRET, ONE_DAY_MS);
        Instant expiresAt = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);

        String token = provider.generateToken(USER_ID, 4, expiresAt);

        assertThat(provider.getExpiration(token)).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("세션 버전이 없는 기존 토큰은 버전 0으로 읽는다")
    void legacyTokenWithoutSessionVersionUsesZero() {
        JwtTokenProvider provider = provider(SECRET, ONE_DAY_MS);
        String token = signedToken(Map.of());

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getSessionVersion(token)).isZero();
    }

    @Test
    @DisplayName("세션 버전은 음수가 아닌 정수 숫자만 허용한다")
    void invalidSessionVersionsAreRejected() {
        JwtTokenProvider provider = provider(SECRET, ONE_DAY_MS);

        assertThat(provider.validateToken(signedToken(Map.of("sv", "1")))).isFalse();
        assertThat(provider.validateToken(signedToken(Map.of("sv", 1.5)))).isFalse();
        assertThat(provider.validateToken(signedToken(Map.of("sv", -1)))).isFalse();
        assertThat(provider.validateToken(signedToken(Map.of("sv", 2_147_483_648L)))).isFalse();
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

    private String signedToken(Map<String, Object> claims) {
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(USER_ID))
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }
}
