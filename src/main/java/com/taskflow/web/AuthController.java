package com.taskflow.web;

import com.taskflow.common.ApiResponse;
import com.taskflow.security.JwtTokenProvider;
import com.taskflow.service.AuthService;
import com.taskflow.web.dto.auth.AuthSession;
import com.taskflow.web.dto.auth.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionCookieService cookieService;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(AuthService authService, SessionCookieService cookieService,
                          JwtTokenProvider jwtTokenProvider) {
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
        return ApiResponse.success(authService.getSession(
                (Long) authentication.getPrincipal(), jwtTokenProvider.getExpiration(token)));
    }

    @PostMapping("/demo")
    public ApiResponse<SessionResponse> demo(HttpServletResponse response) {
        AuthSession session = authService.createDemoSession();
        cookieService.setSession(response, session.token(), session.expiresAt());
        return ApiResponse.success(new SessionResponse(
                true, session.userType().name(), session.expiresAt()));
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
