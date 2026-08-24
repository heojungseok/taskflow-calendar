package com.taskflow.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secretKey,
            @Value("${jwt.expiration}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    // Task 3에서 발급 callsite를 전환한 뒤 제거한다.
    public String generateToken(Long userId) {
        return generateToken(userId, 0);
    }

    public String generateToken(Long userId, Instant expiresAt) {
        return generateToken(userId, 0, expiresAt);
    }

    /**
     * userId와 세션 버전을 기반으로 JWT 생성
     */
    public String generateToken(Long userId, int sessionVersion) {
        return generateToken(userId, sessionVersion, Instant.now().plusMillis(expirationMs));
    }

    public String generateToken(Long userId, int sessionVersion, Instant expiresAt) {
        Date now = new Date();

        return Jwts.builder()
                .subject(String.valueOf(userId))  // userId를 subject로
                .claim("sv", sessionVersion)
                .issuedAt(now)
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 토큰 유효성 검증
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);
            sessionVersion(claims);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // 만료, 변조, 형식 오류 등 모두 false 반환
            return false;
        }
    }

    /**
     * 토큰에서 userId 추출
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return Long.parseLong(claims.getSubject());
    }

    public Instant getExpiration(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    public int getSessionVersion(String token) {
        return sessionVersion(parseClaims(token));
    }

    private int sessionVersion(Claims claims) {
        Object value = claims.get("sv");
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Number number)) {
            throw new MalformedJwtException("Session version must be a number");
        }

        try {
            int sessionVersion = new BigDecimal(number.toString()).intValueExact();
            if (sessionVersion < 0) {
                throw new MalformedJwtException("Session version must not be negative");
            }
            return sessionVersion;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new MalformedJwtException("Session version must be an integer", e);
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
