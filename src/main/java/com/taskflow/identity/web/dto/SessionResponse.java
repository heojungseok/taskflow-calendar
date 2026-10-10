package com.taskflow.identity.web.dto;

import java.time.Instant;

public record SessionResponse(boolean authenticated, String userType, Instant expiresAt) {

    public static SessionResponse anonymous() {
        return new SessionResponse(false, null, null);
    }
}
