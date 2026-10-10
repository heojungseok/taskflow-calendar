package com.taskflow.identity.security;

import com.taskflow.identity.user.Provider;
import java.time.Instant;

public record AuthSession(String token, Long userId, Provider userType, Instant expiresAt) {}
