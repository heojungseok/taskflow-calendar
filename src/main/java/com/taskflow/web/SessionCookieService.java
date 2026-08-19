package com.taskflow.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class SessionCookieService {

    public static final String SESSION_COOKIE = "TASKFLOW_SESSION";
    public static final String OAUTH_STATE_COOKIE = "OAUTH_STATE";

    private final boolean secure;

    public SessionCookieService(@Value("${app.session.secure}") boolean secure) {
        this.secure = secure;
    }

    public void setSession(HttpServletResponse response, String token, Instant expiresAt) {
        add(response, SESSION_COOKIE, token, "/", Duration.between(Instant.now(), expiresAt));
    }

    public void clearSession(HttpServletResponse response) {
        add(response, SESSION_COOKIE, "", "/", Duration.ZERO);
    }

    public void setOAuthState(HttpServletResponse response, String state) {
        add(response, OAUTH_STATE_COOKIE, state, "/api/oauth/google/callback", Duration.ofMinutes(10));
    }

    public void clearOAuthState(HttpServletResponse response) {
        add(response, OAUTH_STATE_COOKIE, "", "/api/oauth/google/callback", Duration.ZERO);
    }

    private void add(HttpServletResponse response, String name, String value, String path, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
