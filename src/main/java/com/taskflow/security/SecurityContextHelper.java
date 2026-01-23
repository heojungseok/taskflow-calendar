package com.taskflow.security;

import com.taskflow.common.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityContextHelper {

    /**
     * 현재 인증된 사용자의 userId를 반환
     * 인증되지 않았으면 예외 throw
     */
    public static Long getCurrentUserId() {
        // SecurityContextHolder에서 Authentication 추출
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Authentication이 null이면 예외
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("User is not authenticated");
        }


        Object principal = authentication.getPrincipal();

        // anonymousUser 문자열 체크 추가
        if ("anonymousUser".equals(principal)) {
            throw new UnauthorizedException("Anonymous user cannot access this resource");
        }

        // 캐스팅 및 반환
        if (principal instanceof Long) {
            return (Long) principal;
        }

        throw new UnauthorizedException("Invalid authentication principal type");
    }
}