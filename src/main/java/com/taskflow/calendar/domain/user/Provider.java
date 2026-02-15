package com.taskflow.calendar.domain.user;

/**
 * 사용자 인증 제공자
 * MVP: GOOGLE만 사용, 추후 LOCAL(이메일/패스워드) 확장 가능
 */
public enum Provider {
    GOOGLE,  // Google OAuth 인증
    LOCAL    // 이메일/패스워드 인증 (추후 확장)
}
