package com.taskflow.identity.security;

import com.taskflow.identity.user.User;
import com.taskflow.identity.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, UserRepository userRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractTokenFromCookie(request);
        boolean tokenValid = token != null && jwtTokenProvider.validateToken(token);
        if (token != null && !tokenValid) {
            logRejected("token_invalid");
        }
        // 2. 토큰이 있고 유효하면
        if (tokenValid) {
            // 3. userId 추출
            Long userId = jwtTokenProvider.getUserIdFromToken(token);
            int tokenVersion = jwtTokenProvider.getSessionVersion(token);
            Optional<User> user = userRepository.findById(userId);
            if (user.isEmpty()) {
                logRejected("user_missing");
                filterChain.doFilter(request, response);
                return;
            }
            if (!user.get().isSessionActive(tokenVersion, Instant.now())) {
                // 로그아웃·연결 해제로 버전이 올라간 토큰과 DEMO 만료를 나눠 남긴다.
                logRejected(user.get().getSessionVersion() != tokenVersion ? "session_revoked" : "session_expired");
                filterChain.doFilter(request, response);
                return;
            }

            Authentication authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());

            // 5. SecurityContext에 설정
            SecurityContextHolder.getContext().setAuthentication(authentication);
            // 요청마다 찍힌다. userId가 로그에 남으므로 debug로 둔다.
            log.debug("Authenticated userId={} for {}", userId, request.getRequestURI());
        }

        // 6. 다음 필터로 전달
        filterChain.doFilter(request, response);
    }

    /** Grafana 보안 Row가 읽는 거부 이유. userId와 토큰 값은 남기지 않는다. */
    private void logRejected(String reason) {
        log.info("Session token rejected. reason={}", reason);
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
