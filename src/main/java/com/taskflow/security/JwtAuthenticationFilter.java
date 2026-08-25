package com.taskflow.security;

import com.taskflow.calendar.domain.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, UserRepository userRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String token = extractTokenFromCookie(request);
        // 2. 토큰이 있고 유효하면
        if (token != null && jwtTokenProvider.validateToken(token)) {
            // 3. userId 추출
            Long userId = jwtTokenProvider.getUserIdFromToken(token);
            int tokenVersion = jwtTokenProvider.getSessionVersion(token);
            boolean sessionActive = userRepository.findById(userId)
                    .map(user -> user.isSessionActive(tokenVersion, Instant.now()))
                    .orElse(false);
            if (!sessionActive) {
                filterChain.doFilter(request, response);
                return;
            }

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

    private String extractTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if ("TASKFLOW_SESSION".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
