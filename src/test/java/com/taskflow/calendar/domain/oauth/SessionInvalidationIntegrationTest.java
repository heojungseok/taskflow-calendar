package com.taskflow.calendar.domain.oauth;

import com.google.api.client.http.HttpResponseException;
import com.taskflow.calendar.domain.user.User;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import com.taskflow.security.JwtTokenProvider;
import com.taskflow.service.AuthService;
import com.taskflow.web.SessionCookieService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "outbox.worker.enabled=false")
@AutoConfigureMockMvc
class SessionInvalidationIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthGoogleTokenRepository tokenRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AuthService authService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mvc;

    @MockitoSpyBean
    private GoogleOAuthService googleOAuthService;

    private Long testUserId;

    @AfterEach
    void cleanUpOwnUser() {
        if (testUserId == null) {
            return;
        }
        tokenRepository.deleteById(testUserId);
        userRepository.deleteById(testUserId);
    }

    @Test
    void invalidGrantDeletesEncryptedLocalTokenDespiteThrownException() throws Exception {
        User user = saveGoogleUser();
        tokenRepository.saveAndFlush(OAuthGoogleToken.create(
                user.getId(), "access-token", "refresh-token",
                LocalDateTime.now().plusHours(1), "openid"));
        doThrow(tokenError(400, "invalid_grant"))
                .when(googleOAuthService).requestTokenRefresh(any(OAuthGoogleToken.class));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(ignored -> googleOAuthService.refreshAccessToken(user.getId())))
                .isInstanceOf(NonRetryableIntegrationException.class);

        assertThat(tokenRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void logoutRejectsOldJwtAndAcceptsRefreshedVersion() throws Exception {
        User user = saveGoogleUser();
        String oldToken = jwtTokenProvider.generateToken(user.getId(), user.getSessionVersion());

        assertOutboxStatus(oldToken, 200);

        authService.logout(user.getId());

        assertOutboxStatus(oldToken, 401);
        User refreshed = userRepository.findById(user.getId()).orElseThrow();
        assertThat(refreshed.getSessionVersion()).isEqualTo(1);
        String newToken = jwtTokenProvider.generateToken(
                refreshed.getId(), refreshed.getSessionVersion());
        assertOutboxStatus(newToken, 200);
    }

    private User saveGoogleUser() {
        User user = userRepository.saveAndFlush(User.createGoogleUser(
                "session-invalidation-" + UUID.randomUUID() + "@example.test", "Session Test"));
        testUserId = user.getId();
        return user;
    }

    private void assertOutboxStatus(String token, int expectedStatus) throws Exception {
        mvc.perform(get("/api/calendar-outbox")
                        .cookie(new Cookie(SessionCookieService.SESSION_COOKIE, token)))
                .andExpect(status().is(expectedStatus));
    }

    private HttpResponseException tokenError(int status, String error) {
        return new HttpResponseException.Builder(
                status, "Google token error", new com.google.api.client.http.HttpHeaders())
                .setContent("{\"error\":\"" + error + "\"}")
                .build();
    }
}
