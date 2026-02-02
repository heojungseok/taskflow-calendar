package com.taskflow.security;

import com.taskflow.common.exception.UnauthorizedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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
        if (authentication == null) {
            throw new UnauthorizedException("User is not authenticated");
        }

        // ✅ 추가: AnonymousAuthenticationToken 체크
        if (authentication instanceof AnonymousAuthenticationToken) {
            throw new UnauthorizedException("Anonymous user cannot access this resource");
        }

        // ✅ 수정: isAuthenticated() 체크는 AnonymousAuthenticationToken 체크 이후에
        if (!authentication.isAuthenticated()) {
            throw new UnauthorizedException("User is not authenticated");
        }

        Object principal = authentication.getPrincipal();

        // anonymousUser 문자열 체크 (이중 안전장치)
        if ("anonymousUser".equals(principal)) {
            throw new UnauthorizedException("Anonymous user cannot access this resource");
        }

        // 캐스팅 및 반환
        if (principal instanceof Long) {
            return (Long) principal;
        }

        // ✅ 추가: String 타입도 처리 (JWT에서 String으로 올 수 있음)
        if (principal instanceof String) {
            try {
                return Long.parseLong((String) principal);
            } catch (NumberFormatException e) {
                throw new UnauthorizedException("Invalid userId format: " + principal);
            }
        }

        throw new UnauthorizedException("Invalid authentication principal type: " + principal.getClass());
    }
}