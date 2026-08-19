package com.taskflow.service;

import com.taskflow.calendar.domain.user.User;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.security.JwtTokenProvider;
import com.taskflow.web.dto.auth.AuthSession;
import com.taskflow.web.dto.auth.SessionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(UserRepository userRepository, JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public AuthSession createDemoSession() {
        Instant expiresAt = Instant.now().plusSeconds(86_400);
        User user = userRepository.save(User.createDemoUser(
                UUID.randomUUID().toString(),
                LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault())));
        return new AuthSession(
                jwtTokenProvider.generateToken(user.getId(), expiresAt),
                user.getId(), user.getProvider(), expiresAt);
    }

    public SessionResponse getSession(Long userId) {
        return userRepository.findById(userId)
                .map(user -> new SessionResponse(
                        true,
                        user.getProvider().name(),
                        user.getExpiresAt() == null
                                ? null
                                : user.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant()))
                .orElseGet(SessionResponse::anonymous);
    }
}
