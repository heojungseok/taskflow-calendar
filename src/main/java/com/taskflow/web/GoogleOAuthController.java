package com.taskflow.web;

import com.taskflow.calendar.domain.oauth.GoogleOAuthService;
import com.taskflow.calendar.domain.oauth.OAuthStateStore;
import com.taskflow.calendar.domain.oauth.dto.AuthorizeUrlResponse;
import com.taskflow.calendar.domain.oauth.dto.GoogleOAuthResult;
import com.taskflow.common.ApiResponse;
import com.taskflow.config.GoogleOAuthProperties;
import com.taskflow.web.dto.auth.AuthSession;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.Authentication;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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
    private final SessionCookieService cookieService;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    /**
     * Google OAuth 인증 URL 생성 (공개 엔드포인트)
     */
    @GetMapping("/authorize")
    public ApiResponse<AuthorizeUrlResponse> getAuthorizeUrl(HttpServletResponse response) {
        String state;
        try {
            state = stateStore.generateState();
        } catch (IllegalStateException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "OAuth temporarily unavailable");
        }
        cookieService.setOAuthState(response, state);

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
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "error", required = false) String oauthError,
            @CookieValue(name = SessionCookieService.OAUTH_STATE_COOKIE, required = false) String cookieState,
            HttpServletResponse response
    ) {
        try {
            if (oauthError != null || code == null
                    || !sameState(state, cookieState) || !stateStore.validateState(state)) {
                throw new IllegalArgumentException("Invalid or expired OAuth state");
            }

            // 2️⃣ Google Token + UserInfo 획득
            GoogleOAuthResult result = googleOAuthService.exchangeCodeAndGetUserInfo(code);

            // 3️⃣ User 조회/생성 + JWT 발급
            AuthSession session = googleOAuthService.loginOrRegister(result);
            cookieService.setSession(response, session.token(), session.expiresAt());

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendBaseUrl + "/oauth/callback"))
                    .build();

        } catch (Exception e) {
            log.warn("OAuth callback failed. errorCode=oauth_failed");

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendBaseUrl + "/oauth/callback?error=oauth_failed"))
                    .build();
        } finally {
            cookieService.clearOAuthState(response);
        }
    }

    private boolean sameState(String queryState, String cookieState) {
        return queryState != null && cookieState != null && MessageDigest.isEqual(
                queryState.getBytes(StandardCharsets.UTF_8),
                cookieState.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/disconnect")
    public ApiResponse<Void> disconnect(Authentication authentication, HttpServletResponse response) {
        try {
            googleOAuthService.disconnect((Long) authentication.getPrincipal());
            return ApiResponse.success(null);
        } finally {
            cookieService.clearSession(response);
        }
    }
}
