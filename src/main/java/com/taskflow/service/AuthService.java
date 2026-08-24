package com.taskflow.service;

import com.taskflow.calendar.domain.user.User;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.security.JwtTokenProvider;
import com.taskflow.web.dto.auth.AuthSession;
import com.taskflow.web.dto.auth.SessionResponse;
import com.taskflow.observability.TaskFlowMetrics;
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
    private final TaskFlowMetrics metrics;

    public AuthService(UserRepository userRepository, JwtTokenProvider jwtTokenProvider,
                       TaskFlowMetrics metrics) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.metrics = metrics;
    }

    @Transactional
    public AuthSession createDemoSession() {
        Instant expiresAt = Instant.now().plusSeconds(86_400);
        User user = userRepository.save(User.createDemoUser(
                UUID.randomUUID().toString(),
                LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault())));
        metrics.demoSessionStarted();
        return new AuthSession(
                jwtTokenProvider.generateToken(user.getId(), user.getSessionVersion(), expiresAt),
                user.getId(), user.getProvider(), expiresAt);
    }

    public SessionResponse getSession(Long userId, Instant tokenExpiresAt) {
        return userRepository.findById(userId)
                .map(user -> new SessionResponse(
                        true,
                        user.getProvider().name(),
                        tokenExpiresAt))
                .orElseGet(SessionResponse::anonymous);
    }
}
