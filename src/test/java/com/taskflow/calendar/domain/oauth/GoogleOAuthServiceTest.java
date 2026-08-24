package com.taskflow.calendar.domain.oauth;

import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpResponseException;
import com.taskflow.calendar.domain.oauth.dto.GoogleOAuthResult;
import com.taskflow.calendar.domain.oauth.exception.MissingRequiredGoogleScopeException;
import com.taskflow.calendar.domain.user.Provider;
import com.taskflow.calendar.domain.user.User;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import com.taskflow.calendar.integration.googlecalendar.exception.RetryableIntegrationException;
import com.taskflow.config.GoogleOAuthProperties;
import com.taskflow.security.JwtTokenProvider;
import com.taskflow.web.dto.auth.AuthSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.time.Instant;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
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

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private GoogleOAuthService service;        // ← @Spy 제거

    private static final Long USER_ID = 4L;
    private static final int SESSION_VERSION = 3;
    private OAuthGoogleToken token;

    @BeforeEach
    void setUp() {
        // 수동 생성 → @RequiredArgsConstructor 생성자 직접 호출
        service = Mockito.spy(new GoogleOAuthService(properties, tokenRepository, userRepository, jwtTokenProvider));
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
    @DisplayName("invalid_grant면 재시도하지 않고 로컬 토큰을 삭제한다")
    void refreshAccessToken_InvalidGrantDeletesLocalToken() throws Exception {
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
        doThrow(tokenError(400, "invalid_grant"))
                .when(service).requestTokenRefresh(any(OAuthGoogleToken.class));

        assertThrows(NonRetryableIntegrationException.class,
                () -> service.refreshAccessToken(USER_ID));
        verify(tokenRepository).deleteByUserId(USER_ID);
    }

    @Test
    @DisplayName("invalid_client는 재시도하지 않지만 로컬 토큰을 보존한다")
    void refreshAccessToken_InvalidClientKeepsLocalToken() throws Exception {
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
        doThrow(tokenError(400, "invalid_client"))
                .when(service).requestTokenRefresh(any(OAuthGoogleToken.class));

        assertThrows(NonRetryableIntegrationException.class,
                () -> service.refreshAccessToken(USER_ID));
        verify(tokenRepository, never()).deleteByUserId(USER_ID);
    }

    @Test
    @DisplayName("400 오류 응답을 파싱할 수 없으면 로컬 토큰을 보존한다")
    void refreshAccessToken_MalformedErrorKeepsLocalToken() throws Exception {
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
        HttpResponseException malformed = new HttpResponseException.Builder(
                400, "Google token error", new com.google.api.client.http.HttpHeaders())
                .setContent("not-json")
                .build();
        doThrow(malformed).when(service).requestTokenRefresh(any(OAuthGoogleToken.class));

        assertThrows(NonRetryableIntegrationException.class,
                () -> service.refreshAccessToken(USER_ID));
        verify(tokenRepository, never()).deleteByUserId(USER_ID);
    }

    @Test
    @DisplayName("Google 500 응답은 재시도하고 로컬 토큰을 보존한다")
    void refreshAccessToken_ServerErrorKeepsLocalToken() throws Exception {
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
        doThrow(tokenError(500, "server_error"))
                .when(service).requestTokenRefresh(any(OAuthGoogleToken.class));

        assertThrows(RetryableIntegrationException.class,
                () -> service.refreshAccessToken(USER_ID));
        verify(tokenRepository, never()).deleteByUserId(USER_ID);
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

    @Test
    void disconnectInvalidatesSessionsAndDeletesLocalTokenWhenGoogleRevokeThrows() throws Exception {
        User user = mock(User.class);
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        doThrow(new IOException("network")).when(service).revokeToken(anyString());

        service.disconnect(USER_ID);

        verify(user).invalidateSessions();
        verify(tokenRepository).deleteByUserId(USER_ID);
    }

    @Test
    void disconnectInvalidatesSessionsAndDeletesLocalTokenWhenGoogleRevokeTimesOut() throws Exception {
        User user = mock(User.class);
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        doThrow(new HttpTimeoutException("timeout")).when(service).revokeToken(anyString());

        service.disconnect(USER_ID);

        verify(user).invalidateSessions();
        verify(tokenRepository).deleteByUserId(USER_ID);
    }

    @Test
    void disconnectInvalidatesSessionsAndDeletesLocalTokenWhenGoogleRevokeReturns500() throws Exception {
        User user = mock(User.class);
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        doReturn(500).when(service).revokeToken(anyString());

        service.disconnect(USER_ID);

        verify(user).invalidateSessions();
        verify(tokenRepository).deleteByUserId(USER_ID);
    }

    @Test
    void disconnectFailsWhenAuthenticatedUserIsMissing() throws Exception {
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        doReturn(204).when(service).revokeToken(anyString());

        assertThrows(IllegalStateException.class, () -> service.disconnect(USER_ID));

        verify(tokenRepository, never()).deleteByUserId(USER_ID);
    }

    @Test
    void loginRejectsBroaderCalendarScopeWhenOwnedScopeIsMissing() {
        GoogleOAuthResult result = new GoogleOAuthResult(
                "user@example.com", "User", "access", "refresh", 3600L,
                "openid https://www.googleapis.com/auth/calendar.events");

        assertThrows(MissingRequiredGoogleScopeException.class,
                () -> service.loginOrRegister(result));

        verifyNoInteractions(userRepository, tokenRepository, jwtTokenProvider);
    }

    @Test
    void loginAcceptsOwnedCalendarScope() {
        GoogleOAuthResult result = new GoogleOAuthResult(
                "user@example.com", "User", "access", "refresh", 3600L,
                "openid https://www.googleapis.com/auth/calendar.events.owned");
        User user = mock(User.class);
        Instant expiresAt = Instant.now().plusSeconds(3600);

        when(user.getId()).thenReturn(USER_ID);
        when(user.getSessionVersion()).thenReturn(SESSION_VERSION);
        when(user.getProvider()).thenReturn(Provider.GOOGLE);
        when(userRepository.findByEmail(result.getEmail())).thenReturn(Optional.of(user));
        when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(jwtTokenProvider.generateToken(USER_ID, SESSION_VERSION)).thenReturn("jwt");
        when(jwtTokenProvider.getExpiration("jwt")).thenReturn(expiresAt);

        AuthSession session = service.loginOrRegister(result);

        assertEquals(new AuthSession("jwt", USER_ID, Provider.GOOGLE, expiresAt), session);
        verify(jwtTokenProvider).generateToken(USER_ID, SESSION_VERSION);
        verify(tokenRepository).save(argThat(saved -> result.getScope().equals(saved.getScope())));
    }

    private HttpResponseException tokenError(int status, String error) {
        return new HttpResponseException.Builder(
                status, "Google token error", new com.google.api.client.http.HttpHeaders())
                .setContent("{\"error\":\"" + error + "\"}")
                .build();
    }
}
