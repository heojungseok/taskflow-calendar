package com.taskflow.calendar.domain.oauth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Google OAuth Token 교환 결과
 */
@Getter
@AllArgsConstructor
public class GoogleOAuthResult {
    private String email;
    private String name;
    private String accessToken;
    private String refreshToken;
    private Long expiresInSeconds;
    private String scope;
}
