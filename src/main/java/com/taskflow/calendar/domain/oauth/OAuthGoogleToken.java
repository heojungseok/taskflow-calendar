package com.taskflow.calendar.domain.oauth;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "oauth_google_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthGoogleToken {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "access_token", columnDefinition = "TEXT", nullable = false)
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT", nullable = false)
    private String refreshToken;

    @Column(name = "expiry_at", nullable = false)
    private LocalDateTime expiryAt;

    @Column(name = "scope", columnDefinition = "TEXT", nullable = false)
    private String scope;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ✅ 생성 메서드
    public static OAuthGoogleToken create(Long userId, String accessToken, String refreshToken, LocalDateTime expiryAt, String scope) {
        OAuthGoogleToken token = new OAuthGoogleToken();
        token.userId = userId;
        token.accessToken = accessToken;
        token.refreshToken = refreshToken;
        token.expiryAt = expiryAt;
        token.scope = scope;
        token.createdAt = LocalDateTime.now();
        token.updatedAt = LocalDateTime.now();
        return token;
    }

    // ✅ 1. 일반적인 갱신 (access_token + expiry만)
    public void updateAccessToken(String newAccessToken, LocalDateTime newExpiryAt) {
        this.accessToken = newAccessToken;
        this.expiryAt = newExpiryAt;
        this.updatedAt = LocalDateTime.now();
    }

    // ✅ 2. Full 갱신 (refresh_token/scope도 바뀔 수 있음)
    public void updateTokens(String newAccessToken, String newRefreshToken, LocalDateTime newExpiryAt, String newScope) {
        this.accessToken = newAccessToken;
        this.expiryAt = newExpiryAt;

        // 있을 때만 교체
        if (newRefreshToken != null && !newRefreshToken.isBlank()) {
            this.refreshToken = newRefreshToken;
        }
        if (newScope != null && !newScope.isBlank()) {
            this.scope = newScope;
        }

        this.updatedAt = LocalDateTime.now();
    }

    // ✅ 3. 만료 확인
    public boolean isExpired() {
        return expiryAt.isBefore(LocalDateTime.now().plusSeconds(60));
    }
}