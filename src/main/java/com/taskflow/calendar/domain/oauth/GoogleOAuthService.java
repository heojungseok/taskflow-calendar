package com.taskflow.calendar.domain.oauth;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.taskflow.config.GoogleOAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
}
