package com.taskflow.identity.web;

import com.taskflow.common.ApiResponse;
import com.taskflow.identity.auth.AuthService;
import com.taskflow.identity.security.AuthSession;
import com.taskflow.identity.security.JwtTokenProvider;
import com.taskflow.identity.web.dto.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionCookieService cookieService;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(
            AuthService authService, SessionCookieService cookieService, JwtTokenProvider jwtTokenProvider) {
        this.authService = authService;
        this.cookieService = cookieService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @GetMapping("/session")
    public ApiResponse<SessionResponse> session(
            Authentication authentication,
            @CookieValue(name = SessionCookieService.SESSION_COOKIE, required = false) String token,
            HttpServletRequest request) {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        csrfToken.getToken();
        if (authentication == null) {
            return ApiResponse.success(SessionResponse.anonymous());
        }
        Instant expiresAt = jwtTokenProvider.getExpiration(token);
        return ApiResponse.success(authService
                .findUserType((Long) authentication.getPrincipal())
                .map(userType -> new SessionResponse(true, userType.name(), expiresAt))
                .orElseGet(SessionResponse::anonymous));
    }

    @PostMapping("/demo")
    public ApiResponse<SessionResponse> demo(HttpServletResponse response) {
        AuthSession session = authService.createDemoSession();
        cookieService.setSession(response, session.token(), session.expiresAt());
        return ApiResponse.success(new SessionResponse(true, session.userType().name(), session.expiresAt()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(Authentication authentication, HttpServletResponse response) {
        try {
            authService.logout((Long) authentication.getPrincipal());
            return ApiResponse.success(null);
        } finally {
            cookieService.clearSession(response);
        }
    }
}
