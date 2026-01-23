package com.taskflow.service;

import com.taskflow.calendar.domain.user.User;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.calendar.domain.user.exception.UserNotFoundException;
import com.taskflow.security.JwtTokenProvider;
import com.taskflow.web.dto.auth.LoginRequest;
import com.taskflow.web.dto.auth.LoginResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(UserRepository userRepository, JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public LoginResponse login(LoginRequest request) {
        // TODO: 1. email로 User 조회
        // TODO: 2. User 없으면 NotFoundException
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UserNotFoundException(request.getEmail()));

        log.info("User logged in successfully: userId={}, email={}", user.getId(), user.getEmail());

        // TODO: 3. JWT 토큰 생성
        String token = jwtTokenProvider.generateToken(user.getId());
        // TODO: 4. LoginResponse 생성 및 반환
        return new LoginResponse(token, user.getId(), user.getEmail(), user.getName());
    }
}