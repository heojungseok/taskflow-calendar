package com.taskflow.web;

import com.taskflow.calendar.domain.oauth.GoogleOAuthService;
import com.taskflow.calendar.domain.oauth.OAuthStateStore;
import com.taskflow.calendar.domain.oauth.dto.GoogleOAuthResult;
import com.taskflow.calendar.domain.oauth.exception.MissingRequiredGoogleScopeException;
import com.taskflow.calendar.domain.oauth.exception.MissingRefreshTokenException;
import com.taskflow.calendar.domain.user.Provider;
import com.taskflow.calendar.domain.user.User;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.config.GoogleOAuthProperties;
import com.taskflow.config.SecurityConfig;
import com.taskflow.security.JwtTokenProvider;
import com.taskflow.web.dto.auth.AuthSession;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static com.taskflow.calendar.domain.oauth.OAuthStateStore.OAuthAttempt.CONSENT_RETRY;
import static com.taskflow.calendar.domain.oauth.OAuthStateStore.OAuthAttempt.NORMAL;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GoogleOAuthController.class)
@Import({SecurityConfig.class, SessionCookieService.class})
@TestPropertySource(properties = "app.frontend.base-url=http://frontend.test")
class GoogleOAuthControllerTest {

    private static final String TOKEN = "session-token";

    @Autowired MockMvc mvc;
    @MockitoBean GoogleOAuthProperties properties;
    @MockitoBean GoogleOAuthService googleOAuthService;
    @MockitoBean OAuthStateStore stateStore;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean UserRepository userRepository;

    @Test
    void authorizeBindsStateToHttpOnlyCallbackCookie() throws Exception {
        given(stateStore.generateState(NORMAL)).willReturn("state-value");
        stubOAuthProperties();

        mvc.perform(get("/api/oauth/google/authorize"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("OAUTH_STATE", "state-value"))
                .andExpect(cookie().httpOnly("OAUTH_STATE", true))
                .andExpect(cookie().path("OAUTH_STATE", "/api/oauth/google/callback"))
                .andExpect(jsonPath("$.data.authorizeUrl")
                        .value(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("calendar.events.owned"),
                                org.hamcrest.Matchers.containsString("access_type=offline"),
                                org.hamcrest.Matchers.containsString("include_granted_scopes=true"),
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.containsString("prompt=consent")))));
    }

    @Test
    void explicitReconsentUsesServerMarkedStateAndConsentPrompt() throws Exception {
        given(stateStore.generateState(CONSENT_RETRY)).willReturn("retry-state");
        stubOAuthProperties();

        mvc.perform(get("/api/oauth/google/reconsent"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("OAUTH_STATE", "retry-state"))
                .andExpect(jsonPath("$.data.authorizeUrl")
                        .value(org.hamcrest.Matchers.containsString("prompt=consent")));
    }

    @Test
    void authorizeRejectsWhenStateStoreIsFull() throws Exception {
        given(stateStore.generateState(NORMAL)).willThrow(new IllegalStateException("full"));

        mvc.perform(get("/api/oauth/google/authorize"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void callbackRequiresMatchingCookieAndNeverRedirectsWithToken() throws Exception {
        GoogleOAuthResult result = new GoogleOAuthResult(
                "user@example.test", "User", "access", "refresh", 3600L, "scope");
        Instant expiresAt = Instant.now().plusSeconds(3600);
        given(stateStore.consumeState("same")).willReturn(Optional.of(NORMAL));
        given(googleOAuthService.exchangeCodeAndGetUserInfo("code")).willReturn(result);
        given(googleOAuthService.loginOrRegister(result)).willReturn(
                new AuthSession("secret-jwt", 1L, Provider.GOOGLE, expiresAt));

        mvc.perform(get("/api/oauth/google/callback")
                        .param("state", "same")
                        .param("code", "code")
                        .cookie(new Cookie("OAUTH_STATE", "same")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://frontend.test/oauth/callback"))
                .andExpect(cookie().value("TASKFLOW_SESSION", "secret-jwt"))
                .andExpect(cookie().httpOnly("TASKFLOW_SESSION", true))
                .andExpect(cookie().maxAge("OAUTH_STATE", 0));
    }

    @Test
    void mismatchedCookieUsesFixedErrorAndDoesNotConsumeServerState() throws Exception {
        mvc.perform(get("/api/oauth/google/callback")
                        .param("state", "query")
                        .param("code", "code")
                        .cookie(new Cookie("OAUTH_STATE", "cookie")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://frontend.test/oauth/callback?error=oauth_failed"))
                .andExpect(cookie().maxAge("OAUTH_STATE", 0));

        verify(stateStore, never()).consumeState(any());
        verify(googleOAuthService, never()).exchangeCodeAndGetUserInfo(any());
    }

    @Test
    void deniedCallbackAlsoClearsStateCookie() throws Exception {
        given(stateStore.consumeState("same")).willReturn(Optional.of(NORMAL));

        mvc.perform(get("/api/oauth/google/callback")
                        .param("state", "same")
                        .param("error", "access_denied")
                        .cookie(new Cookie("OAUTH_STATE", "same")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://frontend.test/oauth/callback?error=consent_cancelled"))
                .andExpect(cookie().maxAge("OAUTH_STATE", 0));

        verify(stateStore).consumeState("same");
        verify(googleOAuthService, never()).exchangeCodeAndGetUserInfo(any());
    }

    @Test
    void missingCalendarPermissionUsesSpecificError() throws Exception {
        GoogleOAuthResult result = new GoogleOAuthResult(
                "user@example.test", "User", "access", "refresh", 3600L, "openid");
        given(stateStore.consumeState("same")).willReturn(Optional.of(NORMAL));
        given(googleOAuthService.exchangeCodeAndGetUserInfo("code")).willReturn(result);
        given(googleOAuthService.loginOrRegister(result))
                .willThrow(new MissingRequiredGoogleScopeException("calendar.events.owned"));

        mvc.perform(get("/api/oauth/google/callback")
                        .param("state", "same")
                        .param("code", "code")
                        .cookie(new Cookie("OAUTH_STATE", "same")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "http://frontend.test/oauth/callback?error=calendar_permission_required"))
                .andExpect(cookie().maxAge("OAUTH_STATE", 0));
    }

    @Test
    void missingRefreshTokenAutomaticallyRedirectsToOneConsentRetry() throws Exception {
        GoogleOAuthResult result = oauthResult();
        given(stateStore.consumeState("same")).willReturn(Optional.of(NORMAL));
        given(stateStore.generateState(CONSENT_RETRY)).willReturn("retry-state");
        given(googleOAuthService.exchangeCodeAndGetUserInfo("code")).willReturn(result);
        given(googleOAuthService.loginOrRegister(result)).willThrow(new MissingRefreshTokenException());
        stubOAuthProperties();

        mvc.perform(get("/api/oauth/google/callback")
                        .param("state", "same")
                        .param("code", "code")
                        .cookie(new Cookie("OAUTH_STATE", "same")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString("prompt=consent")))
                .andExpect(resultMatcher -> {
                    var cookies = resultMatcher.getResponse().getHeaders("Set-Cookie");
                    assertThat(cookies.get(cookies.size() - 1))
                            .contains("OAUTH_STATE=retry-state")
                            .doesNotContain("Max-Age=0");
                });
    }

    @Test
    void missingRefreshTokenAfterConsentRetryStopsWithFixedError() throws Exception {
        GoogleOAuthResult result = oauthResult();
        given(stateStore.consumeState("same")).willReturn(Optional.of(CONSENT_RETRY));
        given(googleOAuthService.exchangeCodeAndGetUserInfo("code")).willReturn(result);
        given(googleOAuthService.loginOrRegister(result)).willThrow(new MissingRefreshTokenException());

        mvc.perform(get("/api/oauth/google/callback")
                        .param("state", "same")
                        .param("code", "code")
                        .cookie(new Cookie("OAUTH_STATE", "same")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "http://frontend.test/oauth/callback?error=refresh_token_unavailable"))
                .andExpect(cookie().maxAge("OAUTH_STATE", 0));

        verify(stateStore, never()).generateState(any());
    }

    @Test
    void disconnectFailureStillClearsSessionCookie() throws Exception {
        stubAuthenticatedUser();
        willThrow(new IllegalStateException("disconnect failed"))
                .given(googleOAuthService).disconnect(7L);

        mvc.perform(post("/api/oauth/google/disconnect")
                        .with(csrf())
                        .cookie(new Cookie(SessionCookieService.SESSION_COOKIE, TOKEN)))
                .andExpect(status().isInternalServerError())
                .andExpect(cookie().maxAge(SessionCookieService.SESSION_COOKIE, 0));
    }

    @Test
    void disconnectReturnsConfirmedGoogleRevocation() throws Exception {
        stubAuthenticatedUser();
        given(googleOAuthService.disconnect(7L)).willReturn(true);

        mvc.perform(post("/api/oauth/google/disconnect")
                        .with(csrf())
                        .cookie(new Cookie(SessionCookieService.SESSION_COOKIE, TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true))
                .andExpect(cookie().maxAge(SessionCookieService.SESSION_COOKIE, 0));
    }

    @Test
    void disconnectReturnsUnconfirmedGoogleRevocation() throws Exception {
        stubAuthenticatedUser();
        given(googleOAuthService.disconnect(7L)).willReturn(false);

        mvc.perform(post("/api/oauth/google/disconnect")
                        .with(csrf())
                        .cookie(new Cookie(SessionCookieService.SESSION_COOKIE, TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false))
                .andExpect(cookie().maxAge(SessionCookieService.SESSION_COOKIE, 0));
    }

    private void stubAuthenticatedUser() {
        User user = mock(User.class);
        given(jwtTokenProvider.validateToken(TOKEN)).willReturn(true);
        given(jwtTokenProvider.getUserIdFromToken(TOKEN)).willReturn(7L);
        given(jwtTokenProvider.getSessionVersion(TOKEN)).willReturn(3);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(user.isSessionActive(eq(3), any(Instant.class))).willReturn(true);
    }

    private GoogleOAuthResult oauthResult() {
        return new GoogleOAuthResult(
                "user@example.test", "User", "access", null, 3600L,
                "openid https://www.googleapis.com/auth/calendar.events.owned");
    }

    private void stubOAuthProperties() {
        given(properties.getAuthorizationUri()).willReturn("https://accounts.google.test/o/oauth2/auth");
        given(properties.getClientId()).willReturn("client-id");
        given(properties.getRedirectUri()).willReturn("http://backend.test/api/oauth/google/callback");
        given(properties.getScope()).willReturn(
                "openid https://www.googleapis.com/auth/calendar.events.owned");
    }
}
