package com.taskflow.calendar.domain.oauth;

import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpResponseException;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import com.taskflow.calendar.integration.googlecalendar.exception.RetryableIntegrationException;
import com.taskflow.config.GoogleOAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleOAuthServiceTest {

    @Mock
    private OAuthGoogleTokenRepository tokenRepository;

    @Mock
    private GoogleOAuthProperties properties;  // ← 추가

    private GoogleOAuthService service;        // ← @Spy 제거

    private static final Long USER_ID = 4L;
    private OAuthGoogleToken token;

    @BeforeEach
    void setUp() {
        // 수동 생성 → @RequiredArgsConstructor 생성자 직접 호출
        service = Mockito.spy(new GoogleOAuthService(properties, tokenRepository));
        token = OAuthGoogleToken.create(
                USER_ID,
                "old-access-token",
                "valid-refresh-token",
                LocalDateTime.now().plusMinutes(10),
                "https://www.googleapis.com/auth/calendar"
        );
    }

    // =========================================================
    // ① 갱신 성공
    // =========================================================
    @Test
    @DisplayName("refreshAccessToken_성공_새 토큰이 저장된다")
    void refreshAccessToken_성공() throws Exception {
        // given
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));

        GoogleTokenResponse mockResponse = new GoogleTokenResponse();
        mockResponse.set("access_token", "new-access-token");
        mockResponse.set("expires_in", 3600L);

        // ✅ requestTokenRefresh만 스텁
        doReturn(mockResponse).when(service).requestTokenRefresh(any(OAuthGoogleToken.class));

        // when
        service.refreshAccessToken(USER_ID);

        // then
        verify(tokenRepository).save(argThat(t ->
                t.getAccessToken().equals("new-access-token")
                        && t.getExpiryAt().isAfter(LocalDateTime.now())
        ));
    }

    // =========================================================
    // ② Refresh Token 만료 (NonRetryable)
    // =========================================================
    @Test
    @DisplayName("refreshAccessToken_RefreshToken만료_NonRetryableException 발생")
    void refreshAccessToken_RefreshToken만료() throws Exception {
        // given
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));

        // ✅ requestTokenRefresh에서 HttpResponseException 발생 (400)
        doThrow(new HttpResponseException.Builder(
                400, "Bad Request", new com.google.api.client.http.HttpHeaders()).build())
                .when(service).requestTokenRefresh(any(OAuthGoogleToken.class));

        // when & then
        assertThrows(NonRetryableIntegrationException.class,
                () -> service.refreshAccessToken(USER_ID));
    }

    // =========================================================
    // ③ 동시 갱신 (OptimisticLockingFailure)
    // =========================================================
    @Test
    @DisplayName("refreshAccessToken_동시갱신_예외 밖으로 나오지 않는다")
    void refreshAccessToken_동시갱신_예외밖으로나오지않는다() throws Exception {
        // given
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));

        GoogleTokenResponse mockResponse = new GoogleTokenResponse();
        mockResponse.set("access_token", "new-access-token");
        mockResponse.set("expires_in", 3600L);
        doReturn(mockResponse).when(service).requestTokenRefresh(any(OAuthGoogleToken.class));

        // save에서 OptimisticLockingFailure 발생
        when(tokenRepository.save(any())).thenThrow(
                new ObjectOptimisticLockingFailureException(OAuthGoogleToken.class, 1L));

        // when & then: 예외가 밖으로 나오지 않아야 한다
        assertDoesNotThrow(() -> service.refreshAccessToken(USER_ID));
    }

    // =========================================================
    // ④ 토큰 조회 실패
    // =========================================================
    @Test
    @DisplayName("refreshAccessToken_토큰없음_NonRetryableException 발생")
    void refreshAccessToken_토큰없음() {
        // given
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThrows(NonRetryableIntegrationException.class,
                () -> service.refreshAccessToken(USER_ID));
    }
}