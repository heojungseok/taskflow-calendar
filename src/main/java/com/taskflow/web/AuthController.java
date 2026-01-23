package com.taskflow.web;

import com.taskflow.common.ApiResponse;
import com.taskflow.service.AuthService;
import com.taskflow.web.dto.auth.LoginRequest;
import com.taskflow.web.dto.auth.LoginResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        // TODO: authService.login() 호출
        LoginResponse loginResponse = authService.login(request);
        // TODO: ApiResponse.success() 반환
        return ApiResponse.success(loginResponse);
    }
}