package com.taskflow.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. Authorization 헤더에서 토큰 추출
        String token = extractTokenFromHeader(request);
        // 2. 토큰이 있고 유효하면
        if (token != null && jwtTokenProvider.validateToken(token)) {
            // 3. userId 추출
            Long userId = jwtTokenProvider.getUserIdFromToken(token);
            // 4. Authentication 객체 생성
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    Collections.emptyList()
            );

            // 5. SecurityContext에 설정
            SecurityContextHolder.getContext().setAuthentication(authentication);
            // 요청마다 찍힌다. userId가 로그에 남으므로 debug로 둔다.
            log.debug("Authenticated userId={} for {}", userId, request.getRequestURI());
        }

        // 6. 다음 필터로 전달
        filterChain.doFilter(request, response);
    }

    private String extractTokenFromHeader(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}