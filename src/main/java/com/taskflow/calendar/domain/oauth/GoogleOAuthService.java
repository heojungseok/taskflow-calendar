package com.taskflow.calendar.domain.oauth;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.taskflow.calendar.domain.oauth.dto.GoogleOAuthResult;
import com.taskflow.calendar.domain.oauth.exception.MissingRequiredGoogleScopeException;
import com.taskflow.calendar.domain.user.User;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import com.taskflow.calendar.integration.googlecalendar.exception.RetryableIntegrationException;
import com.taskflow.config.GoogleOAuthProperties;
import com.taskflow.security.JwtTokenProvider;
import com.taskflow.web.dto.auth.AuthSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GoogleOAuthService {

    private static final String CALENDAR_EVENTS_SCOPE =
            "https://www.googleapis.com/auth/calendar.events.owned";

    private final GoogleOAuthProperties properties;
    private final OAuthGoogleTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public void disconnect(Long userId) {
        tokenRepository.findByUserId(userId).ifPresent(token -> {
            try {
                revokeToken(token.getRefreshToken());
            } catch (Exception e) {
                log.warn("Google token revoke failed. userId={}, errorType={}",
                        userId, e.getClass().getSimpleName());
            }
        });
        tokenRepository.deleteByUserId(userId);
    }

    protected void revokeToken(String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/revoke"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)))
                .build();
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
    }

    public void exchangeCodeForToken(String code, Long userId) {
        log.info("Exchanging code for token. userId={}", userId);

        try {
            // request 객체 생성
            GoogleAuthorizationCodeTokenRequest request = new GoogleAuthorizationCodeTokenRequest(
                    new NetHttpTransport(), // Http 통신용
                    JacksonFactory.getDefaultInstance(), // JSON 파싱용
                    properties.getTokenUri(), // Google Token Endpoint
                    properties.getClientId(), // App ID
                    properties.getClientSecret(), // App Secret
                    code, // Google에서 온 code
                    properties.getRedirectUri() // callback URL
            );
            // API 호출
            GoogleTokenResponse response = request.execute();

            log.info("Token received. accessToken exists={}, refreshToken exists={}",
                    response.getAccessToken() != null,
                    response.getRefreshToken() != null
            );

            String accessToken = response.getAccessToken();
            String refreshToken = response.getRefreshToken(); // null 가능
            Long expiresInSeconds = response.getExpiresInSeconds();
            String scope = response.getScope();

            // 만료 시각 계산
            LocalDateTime expiryAt = LocalDateTime.now().plusSeconds(expiresInSeconds);

            log.info("Token details. expiresIn={}s, expiryAt={}, scope={}", expiresInSeconds, expiryAt, scope);

            Optional<OAuthGoogleToken> existingToken = tokenRepository.findByUserId(userId);

            if (existingToken.isPresent()) {
                // 이미 존재
                log.info("Updating existing token. userId={}", userId);
                OAuthGoogleToken token = existingToken.get();
                token.updateTokens(accessToken, refreshToken, expiryAt, scope);
                // Dirty Checking 자동 UPDATE
            } else {
                // 없으니 새로 생성
                log.info("Creating new token. userId={}", userId);

                if (refreshToken == null) {
                    // 최초 생성 시 refresh_token 없으면 에러
                    throw new IllegalArgumentException("Refresh token is required for initial authentication");
                }

                OAuthGoogleToken token = OAuthGoogleToken.create(userId, accessToken, refreshToken, expiryAt, scope);
                tokenRepository.save(token);
            }

            log.info("Token saved successfully. userId={}", userId);

        } catch (IOException e) {
            log.error("Token exchange failed. userId={}", userId, e);
            throw new RuntimeException("Failed to exchange code for token", e);
        }

    }
    /**
     * Refresh token을 사용해서 새 access token 발급
     *
     * @param userId 사용자 ID
     * @throws NonRetryableIntegrationException refresh token 만료/폐기 시
     * @throws RetryableIntegrationException 일시적 네트워크 오류 시
     */
    public void refreshAccessToken(Long userId) {
        log.info("Refreshing access token. userId={}", userId);

        // 토큰 조회
        OAuthGoogleToken token = tokenRepository.findByUserId(userId)
                .orElseThrow(() -> new NonRetryableIntegrationException("No access token found for userId: " + userId, 0));

        try {
            // Google API로 갱신 요청
            GoogleTokenResponse response = requestTokenRefresh(token);

            // 새 토큰으로 업데이트 (낙관적 락 활용)
            token.updateAccessToken(response.getAccessToken(),
                    LocalDateTime.now().plusSeconds(response.getExpiresInSeconds()));

            tokenRepository.save(token);
        } catch (OptimisticLockingFailureException e) {
            log.info("Token already refreshed by another thread");
        } catch (HttpResponseException e) {
            if (e.getStatusCode() == 400 || e.getStatusCode() == 401) {
                throw new NonRetryableIntegrationException("Refresh token 만료 또는 폐기.", e.getStatusCode(), e);
            }
            throw new RetryableIntegrationException("Token refresh 일시적 실패", e);
        } catch (IOException e) {
            throw new RetryableIntegrationException("Token refresh 실패", e);
        }

    }

    /**
     * Google API에 실제 갱신 요청을 보내는 부분
     * 테스트에서 모킹 대상
     */
    protected GoogleTokenResponse requestTokenRefresh(OAuthGoogleToken token) throws IOException {
        return new GoogleRefreshTokenRequest(
                new NetHttpTransport(),
                JacksonFactory.getDefaultInstance(),
                token.getRefreshToken(),
                properties.getClientId(),
                properties.getClientSecret()
        ).execute();
    }

    /**
     * MVP: Code → Token + ID Token 파싱 (이메일/이름 추출)
     */
    public GoogleOAuthResult exchangeCodeAndGetUserInfo(String code) {
        log.info("Exchanging code for token and extracting user info");

        try {
            GoogleAuthorizationCodeTokenRequest request = new GoogleAuthorizationCodeTokenRequest(
                    new NetHttpTransport(),
                    JacksonFactory.getDefaultInstance(),
                    properties.getTokenUri(),
                    properties.getClientId(),
                    properties.getClientSecret(),
                    code,
                    properties.getRedirectUri()
            );

            GoogleTokenResponse response = request.execute();

            // ID Token에서 이메일/이름 추출
            GoogleIdToken idToken = response.parseIdToken();
            GoogleIdToken.Payload payload = idToken.getPayload();

            String email = payload.getEmail();
            Boolean emailVerified = payload.getEmailVerified();
            String name = (String) payload.get("name");

            log.info("Google user info extracted. emailVerified={}", emailVerified);

            if (emailVerified == null || !emailVerified) {
                throw new IllegalStateException("Email not verified by Google");
            }

            return new GoogleOAuthResult(
                    email,
                    name,
                    response.getAccessToken(),
                    response.getRefreshToken(),
                    response.getExpiresInSeconds(),
                    response.getScope()
            );

        } catch (IOException e) {
            log.error("Google token exchange failed. errorType={}", e.getClass().getSimpleName());
            throw new RuntimeException("Google OAuth failed", e);
        }
    }

    /**
     * MVP: User 조회/생성 + JWT 발급
     */
    public AuthSession loginOrRegister(GoogleOAuthResult result) {
        log.info("Processing Google login or register");

        requireCalendarEventsScope(result.getScope());

        // 1️⃣ User 조회 (email)
        Optional<User> userOpt = userRepository.findByEmail(result.getEmail());

        User user;
        if (userOpt.isPresent()) {
            user = userOpt.get();
            log.info("Existing Google user logged in. userId={}", user.getId());
        } else {
            // 회원가입
            user = User.createGoogleUser(result.getEmail(), result.getName());
            user = userRepository.save(user);
            log.info("New Google user registered. userId={}", user.getId());
        }

        // 2️⃣ OAuthGoogleToken 저장/업데이트
        saveOrUpdateToken(user.getId(), result);

        // 3️⃣ JWT 발급
        String jwt = jwtTokenProvider.generateToken(user.getId());
        Instant expiresAt = jwtTokenProvider.getExpiration(jwt);
        log.info("JWT issued. userId={}", user.getId());

        return new AuthSession(jwt, user.getId(), user.getProvider(), expiresAt);
    }

    private void requireCalendarEventsScope(String grantedScopes) {
        boolean granted = grantedScopes != null && Arrays.stream(grantedScopes.trim().split("\\s+"))
                .anyMatch(CALENDAR_EVENTS_SCOPE::equals);
        if (!granted) {
            throw new MissingRequiredGoogleScopeException(CALENDAR_EVENTS_SCOPE);
        }
    }

    /**
     * OAuthGoogleToken 저장/업데이트 (공통 로직)
     */
    private void saveOrUpdateToken(Long userId, GoogleOAuthResult result) {
        LocalDateTime expiryAt = LocalDateTime.now().plusSeconds(result.getExpiresInSeconds());
        Optional<OAuthGoogleToken> existingToken = tokenRepository.findByUserId(userId);

        if (existingToken.isPresent()) {
            OAuthGoogleToken token = existingToken.get();
            token.updateTokens(
                    result.getAccessToken(),
                    result.getRefreshToken(),
                    expiryAt,
                    result.getScope()
            );
            log.info("Updated existing OAuth token. userId={}", userId);
        } else {
            if (result.getRefreshToken() == null) {
                throw new IllegalArgumentException("Refresh token required for initial authentication");
            }
            OAuthGoogleToken token = OAuthGoogleToken.create(
                    userId,
                    result.getAccessToken(),
                    result.getRefreshToken(),
                    expiryAt,
                    result.getScope()
            );
            tokenRepository.save(token);
            log.info("Created new OAuth token. userId={}", userId);
        }
    }
}
