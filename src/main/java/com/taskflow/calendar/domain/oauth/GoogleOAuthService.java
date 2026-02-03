package com.taskflow.calendar.domain.oauth;

import com.google.api.client.http.HttpResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import com.taskflow.calendar.integration.googlecalendar.exception.RetryableIntegrationException;
import com.taskflow.config.GoogleOAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GoogleOAuthService {

    private final GoogleOAuthProperties properties;
    private final OAuthGoogleTokenRepository tokenRepository;

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
}
