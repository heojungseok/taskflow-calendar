package com.taskflow.web;

import com.taskflow.common.ApiResponse;
import com.taskflow.service.AuthService;
import com.taskflow.web.dto.auth.LoginRequest;
import com.taskflow.web.dto.auth.LoginResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP에서는 비활성화
 * 추후 LOCAL 인증 (이메일/패스워드) 추가 시 복구
 */
@Deprecated
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * MVP에서는 사용 안 함 - Google OAuth로 로그인
     */
    @Deprecated
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        throw new UnsupportedOperationException("Use Google OAuth for login");
    }
}