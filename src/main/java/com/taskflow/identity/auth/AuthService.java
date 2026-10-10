package com.taskflow.identity.auth;

import com.taskflow.identity.security.AuthSession;
import com.taskflow.identity.security.JwtTokenProvider;
import com.taskflow.identity.user.Provider;
import com.taskflow.identity.user.User;
import com.taskflow.identity.user.UserRepository;
import com.taskflow.observability.TaskFlowMetrics;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TaskFlowMetrics metrics;

    public AuthService(UserRepository userRepository, JwtTokenProvider jwtTokenProvider, TaskFlowMetrics metrics) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.metrics = metrics;
    }

    @Transactional
    public AuthSession createDemoSession() {
        Instant expiresAt = Instant.now().plusSeconds(86_400).truncatedTo(ChronoUnit.SECONDS);
        User user = userRepository.save(User.createDemoUser(UUID.randomUUID().toString(), expiresAt));
        metrics.demoSessionStarted();
        return new AuthSession(
                jwtTokenProvider.generateToken(user.getId(), user.getSessionVersion(), expiresAt),
                user.getId(),
                user.getProvider(),
                expiresAt);
    }

    public Optional<Provider> findUserType(Long userId) {
        return userRepository.findById(userId).map(User::getProvider);
    }

    @Transactional
    public void logout(Long userId) {
        User user = userRepository
                .findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
        user.invalidateSessions();
    }
}
