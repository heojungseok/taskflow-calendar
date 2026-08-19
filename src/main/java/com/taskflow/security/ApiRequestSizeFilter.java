package com.taskflow.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class ApiRequestSizeFilter extends OncePerRequestFilter {

    private static final long MAX_BODY_BYTES = 64 * 1024;
    private static final Set<String> BODY_METHODS = Set.of(
            HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name());

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // ponytail: chunked body의 최종 상한은 공개 Nginx가 맡는다. backend 직접 공개 시 counting wrapper로 올린다.
        if (request.getRequestURI().startsWith("/api/")
                && BODY_METHODS.contains(request.getMethod())
                && request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
