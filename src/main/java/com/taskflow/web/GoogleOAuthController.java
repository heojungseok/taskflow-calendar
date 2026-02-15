package com.taskflow.web;

import com.taskflow.calendar.domain.oauth.GoogleOAuthService;
import com.taskflow.calendar.domain.oauth.OAuthStateStore;
import com.taskflow.calendar.domain.oauth.dto.AuthorizeUrlResponse;
import com.taskflow.calendar.domain.oauth.dto.GoogleOAuthResult;
import com.taskflow.common.ApiResponse;
import com.taskflow.config.GoogleOAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Google OAuth 2.0 인증 Controller
 * MVP: Google 로그인으로 회원가입/로그인 통합
 */
@Slf4j
@RestController
@RequestMapping("/api/oauth/google")
@RequiredArgsConstructor
public class GoogleOAuthController {

    private final GoogleOAuthProperties properties;
    private final GoogleOAuthService googleOAuthService;
    private final OAuthStateStore stateStore;

    /**
     * Google OAuth 인증 URL 생성 (공개 엔드포인트)
     */
    @GetMapping("/authorize")
    public ApiResponse<AuthorizeUrlResponse> getAuthorizeUrl() {
        // MVP: state에 userId 포함 안 함 (UUID만)
        String state = stateStore.generateState();

        log.info("Generating authorize URL. state={}", state);

        String authorizeUrl = UriComponentsBuilder
                .fromHttpUrl(properties.getAuthorizationUri())
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", properties.getScope())
                .queryParam("state", state)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .toUriString();

        return ApiResponse.success(new AuthorizeUrlResponse(authorizeUrl));
    }

    /**
     * Google OAuth 콜백 (공개 엔드포인트)
     * User 조회/생성 + JWT 발급 + 프론트엔드 리다이렉트
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> handleCallback(
            @RequestParam("state") String state,
            @RequestParam("code") String code
    ) {
        log.info("OAuth callback received. state={}", state);

        try {
            // 1️⃣ State 검증 (CSRF 방어)
            if (!stateStore.validateState(state)) {
                throw new IllegalArgumentException("Invalid or expired OAuth state");
            }

            // 2️⃣ Google Token + UserInfo 획득
            GoogleOAuthResult result = googleOAuthService.exchangeCodeAndGetUserInfo(code);

            // 3️⃣ User 조회/생성 + JWT 발급
            String jwt = googleOAuthService.loginOrRegister(result);

            // 4️⃣ 프론트엔드로 리다이렉트 (JWT 전달)
            String redirectUrl = String.format(
                    "http://localhost:3000/oauth/callback?token=%s",
                    jwt
            );

            log.info("OAuth login successful. Redirecting to frontend");

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(redirectUrl))
                    .build();

        } catch (Exception e) {
            log.error("OAuth callback failed", e);

            String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown OAuth error";
            String errorUrl = "http://localhost:3000/oauth/callback?error=" +
                    URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(errorUrl))
                    .build();
        }
    }
}
