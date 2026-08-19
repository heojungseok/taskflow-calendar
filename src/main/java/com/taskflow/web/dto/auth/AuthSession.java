package com.taskflow.web.dto.auth;

import com.taskflow.calendar.domain.user.Provider;

import java.time.Instant;

public record AuthSession(String token, Long userId, Provider userType, Instant expiresAt) {
}
