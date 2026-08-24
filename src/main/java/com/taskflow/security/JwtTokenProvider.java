package com.taskflow.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SecretKey key;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secretKey,
            @Value("${jwt.expiration}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
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
            sessionVersion(token, claims);
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
        return sessionVersion(token, parseClaims(token));
    }

    private int sessionVersion(String token, Claims claims) {
        if (!claims.containsKey("sv")) {
            if (hasSessionVersionClaim(token)) {
                throw new MalformedJwtException("Session version must not be null");
            }
            return 0;
        }
        Object value = claims.get("sv");
        int sessionVersion;
        if (value instanceof Integer integer) {
            sessionVersion = integer;
        } else if (value instanceof Long longValue
                && longValue >= 0
                && longValue <= Integer.MAX_VALUE) {
            sessionVersion = longValue.intValue();
        } else {
            throw new MalformedJwtException("Session version must be an integer");
        }
        if (sessionVersion < 0) {
            throw new MalformedJwtException("Session version must not be negative");
        }
        return sessionVersion;
    }

    private boolean hasSessionVersionClaim(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return payload != null && payload.has("sv");
        } catch (IOException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            throw new MalformedJwtException("Invalid JWT payload", e);
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
