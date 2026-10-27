package com.taskflow.identity.web;

import static com.taskflow.identity.oauth.OAuthStateStore.OAuthAttempt.CONSENT_RETRY;
import static com.taskflow.identity.oauth.OAuthStateStore.OAuthAttempt.NORMAL;

import com.taskflow.common.ApiResponse;
import com.taskflow.identity.oauth.GoogleOAuthProperties;
import com.taskflow.identity.oauth.GoogleOAuthService;
import com.taskflow.identity.oauth.OAuthStateStore;
import com.taskflow.identity.oauth.dto.AuthorizeUrlResponse;
import com.taskflow.identity.oauth.dto.GoogleOAuthResult;
import com.taskflow.identity.oauth.exception.MissingRefreshTokenException;
import com.taskflow.identity.oauth.exception.MissingRequiredGoogleScopeException;
import com.taskflow.identity.security.AuthSession;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

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
        return createAuthorizeResponse(response, NORMAL);
    }

    @GetMapping("/reconsent")
    public ApiResponse<AuthorizeUrlResponse> getReconsentUrl(HttpServletResponse response) {
        return createAuthorizeResponse(response, CONSENT_RETRY);
    }

    private ApiResponse<AuthorizeUrlResponse> createAuthorizeResponse(
            HttpServletResponse response, OAuthStateStore.OAuthAttempt attempt) {
        String state;
        try {
            state = stateStore.generateState(attempt);
        } catch (IllegalStateException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "OAuth temporarily unavailable");
        }
        cookieService.setOAuthState(response, state);

        return ApiResponse.success(new AuthorizeUrlResponse(buildAuthorizeUrl(state, attempt)));
    }

    private String buildAuthorizeUrl(String state, OAuthStateStore.OAuthAttempt attempt) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(properties.getAuthorizationUri())
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", properties.getScope())
                .queryParam("state", state)
                .queryParam("access_type", "offline")
                .queryParam("include_granted_scopes", "true");

        if (attempt == CONSENT_RETRY) {
            builder.queryParam("prompt", "consent");
        }
        return builder.toUriString();
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
            HttpServletResponse response) {
        OAuthStateStore.OAuthAttempt attempt;
        try {
            if (!sameState(state, cookieState)) {
                throw new IllegalArgumentException("Invalid or expired OAuth state");
            }
            attempt = stateStore
                    .consumeState(state)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid or expired OAuth state"));
        } catch (Exception e) {
            cookieService.clearOAuthState(response);
            return callbackFailed("state_invalid", "oauth_failed");
        }

        cookieService.clearOAuthState(response);

        if ("access_denied".equals(oauthError)) {
            return callbackFailed("consent_cancelled", "consent_cancelled");
        }
        if (oauthError != null) {
            return callbackFailed("google_error", "oauth_failed");
        }
        if (code == null || code.isBlank()) {
            return callbackFailed("code_missing", "oauth_failed");
        }

        try {

            // 2️⃣ Google Token + UserInfo 획득
            GoogleOAuthResult result = googleOAuthService.exchangeCodeAndGetUserInfo(code);

            // 3️⃣ User 조회/생성 + JWT 발급
            AuthSession session = googleOAuthService.loginOrRegister(result);
            cookieService.setSession(response, session.token(), session.expiresAt());

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendBaseUrl + "/oauth/callback"))
                    .build();

        } catch (MissingRefreshTokenException e) {
            if (attempt == NORMAL) {
                try {
                    String retryState = stateStore.generateState(CONSENT_RETRY);
                    cookieService.setOAuthState(response, retryState);
                    return ResponseEntity.status(HttpStatus.FOUND)
                            .location(URI.create(buildAuthorizeUrl(retryState, CONSENT_RETRY)))
                            .build();
                } catch (IllegalStateException stateCapacityExceeded) {
                    return callbackFailed("state_store_full", "oauth_failed");
                }
            }
            return callbackFailed("refresh_token_unavailable", "refresh_token_unavailable");
        } catch (MissingRequiredGoogleScopeException e) {
            return callbackFailed("calendar_permission_required", "calendar_permission_required");
        } catch (Exception e) {
            return callbackFailed("server_error", "oauth_failed");
        }
    }

    /** 실패 이유를 Grafana 보안 알림이 읽는 한 줄로 남긴다. code·state·token 값은 남기지 않는다. */
    private ResponseEntity<Void> callbackFailed(String reason, String frontendErrorCode) {
        log.warn("OAuth callback failed. reason={}", reason);
        return redirectToFrontendError(frontendErrorCode);
    }

    private ResponseEntity<Void> redirectToFrontendError(String errorCode) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(frontendBaseUrl + "/oauth/callback?error=" + errorCode))
                .build();
    }

    private boolean sameState(String queryState, String cookieState) {
        return queryState != null
                && cookieState != null
                && MessageDigest.isEqual(
                        queryState.getBytes(StandardCharsets.UTF_8), cookieState.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/disconnect")
    public ApiResponse<Boolean> disconnect(Authentication authentication, HttpServletResponse response) {
        try {
            return ApiResponse.success(googleOAuthService.disconnect((Long) authentication.getPrincipal()));
        } finally {
            cookieService.clearSession(response);
        }
    }
}
