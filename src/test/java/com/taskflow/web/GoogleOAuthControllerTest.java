package com.taskflow.web;

import com.taskflow.calendar.domain.oauth.GoogleOAuthService;
import com.taskflow.calendar.domain.oauth.OAuthStateStore;
import com.taskflow.calendar.domain.oauth.dto.GoogleOAuthResult;
import com.taskflow.calendar.domain.oauth.exception.MissingRequiredGoogleScopeException;
import com.taskflow.calendar.domain.user.Provider;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GoogleOAuthController.class)
@Import({SecurityConfig.class, SessionCookieService.class})
@TestPropertySource(properties = "app.frontend.base-url=http://frontend.test")
class GoogleOAuthControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean GoogleOAuthProperties properties;
    @MockitoBean GoogleOAuthService googleOAuthService;
    @MockitoBean OAuthStateStore stateStore;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean UserRepository userRepository;

    @Test
    void authorizeBindsStateToHttpOnlyCallbackCookie() throws Exception {
        given(stateStore.generateState()).willReturn("state-value");
        given(properties.getAuthorizationUri()).willReturn("https://accounts.google.test/o/oauth2/auth");
        given(properties.getScope()).willReturn(
                "openid https://www.googleapis.com/auth/calendar.events.owned");

        mvc.perform(get("/api/oauth/google/authorize"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("OAUTH_STATE", "state-value"))
                .andExpect(cookie().httpOnly("OAUTH_STATE", true))
                .andExpect(cookie().path("OAUTH_STATE", "/api/oauth/google/callback"))
                .andExpect(jsonPath("$.data.authorizeUrl")
                        .value(org.hamcrest.Matchers.containsString("calendar.events.owned")));
    }

    @Test
    void authorizeRejectsWhenStateStoreIsFull() throws Exception {
        given(stateStore.generateState()).willThrow(new IllegalStateException("full"));

        mvc.perform(get("/api/oauth/google/authorize"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void callbackRequiresMatchingCookieAndNeverRedirectsWithToken() throws Exception {
        GoogleOAuthResult result = new GoogleOAuthResult(
                "user@example.test", "User", "access", "refresh", 3600L, "scope");
        Instant expiresAt = Instant.now().plusSeconds(3600);
        given(stateStore.validateState("same")).willReturn(true);
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

        verify(stateStore, never()).validateState(any());
        verify(googleOAuthService, never()).exchangeCodeAndGetUserInfo(any());
    }

    @Test
    void deniedCallbackAlsoClearsStateCookie() throws Exception {
        mvc.perform(get("/api/oauth/google/callback")
                        .param("state", "same")
                        .param("error", "access_denied")
                        .cookie(new Cookie("OAUTH_STATE", "same")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://frontend.test/oauth/callback?error=oauth_failed"))
                .andExpect(cookie().maxAge("OAUTH_STATE", 0));
    }

    @Test
    void missingCalendarPermissionUsesSpecificError() throws Exception {
        GoogleOAuthResult result = new GoogleOAuthResult(
                "user@example.test", "User", "access", "refresh", 3600L, "openid");
        given(stateStore.validateState("same")).willReturn(true);
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
}
